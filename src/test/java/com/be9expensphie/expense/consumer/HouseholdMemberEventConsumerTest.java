package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.HouseholdRole;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdMemberEventConsumerTest {

    private static final long MEMBER_ID = 11L;
    private static final long HOUSEHOLD_ID = 3L;
    private static final long USER_ID = 7L;
    private static final String EXPECTED_PUBLISH = "membership-invalidated|" + USER_ID + ":" + HOUSEHOLD_ID;

    @Mock
    private HouseholdMemberSummaryRepository repo;

    private RecordingRedisTemplate redisTemplate;
    private HouseholdMemberEventConsumer consumer;

    /*
     * StringRedisTemplate is a concrete class the inline mock maker cannot
     * instrument on JDK 25, so the publish is recorded by subclassing instead.
     */
    private static class RecordingRedisTemplate extends StringRedisTemplate {
        final List<String> published = new ArrayList<>();
        boolean publishFails;

        @Override
        public Long convertAndSend(String channel, Object message) {
            if (publishFails) {
                throw new RuntimeException("redis down");
            }
            published.add(channel + "|" + message);
            return 1L;
        }
    }

    @BeforeEach
    void setUp() {
        redisTemplate = new RecordingRedisTemplate();
        consumer = new HouseholdMemberEventConsumer(repo, redisTemplate);
    }

    private static HouseholdMemberEvent event(String type) {
        return HouseholdMemberEvent.builder()
                .memberId(MEMBER_ID)
                .householdId(HOUSEHOLD_ID)
                .userId(USER_ID)
                .email("dana@example.com")
                .fullName("Dana")
                .role("ROLE_MEMBER")
                .eventType(type)
                .build();
    }

    private static HouseholdMemberSummary activeRow() {
        return HouseholdMemberSummary.builder()
                .memberId(MEMBER_ID)
                .householdId(HOUSEHOLD_ID)
                .userId(USER_ID)
                .fullName("Dana")
                .role(HouseholdRole.ROLE_MEMBER)
                .build();
    }

    private HouseholdMemberSummary savedRow() {
        ArgumentCaptor<HouseholdMemberSummary> captor =
                ArgumentCaptor.forClass(HouseholdMemberSummary.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void memberLeftStampsRemovedAtAndAnnouncesTheInvalidation() {
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.of(activeRow()));

        consumer.consume(event("MEMBER_LEFT"));

        assertThat(savedRow().getRemovedAt()).isNotNull();
        assertThat(redisTemplate.published).containsExactly(EXPECTED_PUBLISH);
    }

    /** The row is the source of truth for membership, so it must stop matching. */
    @Test
    void memberLeftRetiresTheRowEvenThoughTheNameIsKept() {
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.of(activeRow()));

        consumer.consume(event("MEMBER_LEFT"));

        HouseholdMemberSummary saved = savedRow();
        assertThat(saved.getRemovedAt()).isNotNull();
        assertThat(saved.getFullName()).isEqualTo("Dana");
        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    void memberLeftForAnUnknownMemberDoesNotThrowAndStillAnnounces() {
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> consumer.consume(event("MEMBER_LEFT"))).doesNotThrowAnyException();

        verify(repo, never()).save(any());
        assertThat(redisTemplate.published).containsExactly(EXPECTED_PUBLISH);
    }

    /** A redelivery must not move removedAt, but must still announce the invalidation. */
    @Test
    void memberLeftIsIdempotentOnAnAlreadyRetiredRow() {
        Instant original = Instant.parse("2026-01-01T00:00:00Z");
        HouseholdMemberSummary retired = activeRow();
        retired.setRemovedAt(original);
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.of(retired));

        consumer.consume(event("MEMBER_LEFT"));

        assertThat(retired.getRemovedAt()).isEqualTo(original);
        verify(repo, never()).save(any());
        assertThat(redisTemplate.published).containsExactly(EXPECTED_PUBLISH);
    }

    /** Without this, a re-join keeps the tombstone and the member is locked out for good. */
    @Test
    void memberJoinedClearsTheTombstoneOnAReJoin() {
        HouseholdMemberSummary retired = activeRow();
        retired.setRemovedAt(Instant.parse("2026-01-01T00:00:00Z"));
        retired.setFullName("Old Name");
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.of(retired));

        consumer.consume(event("MEMBER_JOINED"));

        HouseholdMemberSummary saved = savedRow();
        assertThat(saved.getRemovedAt()).isNull();
        assertThat(saved.getFullName()).isEqualTo("Dana");
        assertThat(redisTemplate.published).containsExactly(EXPECTED_PUBLISH);
    }

    @Test
    void memberJoinedInsertsAnActiveRowWhenNoneExists() {
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.empty());

        consumer.consume(event("MEMBER_JOINED"));

        HouseholdMemberSummary saved = savedRow();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getHouseholdId()).isEqualTo(HOUSEHOLD_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getRole()).isEqualTo(HouseholdRole.ROLE_MEMBER);
        assertThat(saved.getRemovedAt()).isNull();
        assertThat(redisTemplate.published).containsExactly(EXPECTED_PUBLISH);
    }

    /*
     * The database write has already committed by the time the publish runs, so
     * letting the exception escape would have Kafka redeliver a completed write
     * for nothing. Replicas fall back to the cache TTL instead.
     */
    @Test
    void aFailedPublishDoesNotFailTheListener() {
        when(repo.findById(MEMBER_ID)).thenReturn(Optional.of(activeRow()));
        redisTemplate.publishFails = true;

        assertThatCode(() -> consumer.consume(event("MEMBER_LEFT"))).doesNotThrowAnyException();

        assertThat(savedRow().getRemovedAt()).isNotNull();
    }

    @Test
    void unhandledEventTypesTouchNothing() {
        consumer.consume(event("ROLE_CHANGED"));

        verifyNoInteractions(repo);
        assertThat(redisTemplate.published).isEmpty();
    }

    @Test
    void aMalformedEventIsIgnoredRatherThanThrown() {
        assertThatCode(() -> consumer.consume(event(null))).doesNotThrowAnyException();
        assertThatCode(() -> consumer.consume(null)).doesNotThrowAnyException();

        verifyNoInteractions(repo);
        assertThat(redisTemplate.published).isEmpty();
    }
}
