package com.be9expensphie.expense.listener;

import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.be9expensphie.expense.service.HouseholdMembershipCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives the cache through the real HouseholdMembershipCache rather than a mock
 * of it: the thing worth asserting is that the next membership question reaches
 * the database again, not that a method was called.
 */
@ExtendWith(MockitoExtension.class)
class MembershipInvalidationListenerTest {

    private static final long USER_ID = 7L;
    private static final long HOUSEHOLD_ID = 3L;
    private static final String CHANNEL = "membership-invalidated";

    @Mock
    private HouseholdMemberSummaryRepository repo;

    private HouseholdMembershipCache cache;
    private MembershipInvalidationListener listener;

    @BeforeEach
    void setUp() {
        cache = new HouseholdMembershipCache(repo);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 30L);
        ReflectionTestUtils.setField(cache, "maxSize", 1000L);
        /* Package-private on HouseholdMembershipCache; @PostConstruct normally calls it. */
        ReflectionTestUtils.invokeMethod(cache, "initCache");
        listener = new MembershipInvalidationListener(cache);
    }

    private void deliver(String body) {
        listener.onMessage(
                new DefaultMessage(CHANNEL.getBytes(StandardCharsets.UTF_8),
                        body.getBytes(StandardCharsets.UTF_8)),
                null);
    }

    private void memberRowExists() {
        when(repo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(Optional.of(HouseholdMemberSummary.builder()
                        .memberId(11L)
                        .householdId(HOUSEHOLD_ID)
                        .userId(USER_ID)
                        .fullName("Dana")
                        .build()));
    }

    @Test
    void aDeliveredMessageForcesTheNextMembershipCheckBackToTheDatabase() {
        memberRowExists();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        verify(repo, times(1)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);

        deliver(USER_ID + ":" + HOUSEHOLD_ID);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        verify(repo, times(2)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    @Test
    void anUnrelatedPairIsLeftCached() {
        memberRowExists();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();

        deliver("99:" + HOUSEHOLD_ID);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        verify(repo, times(1)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    /*
     * An exception escaping onMessage kills the delivery for every other
     * subscriber on the container's thread, so a malformed body must be dropped
     * quietly rather than thrown.
     */
    @Test
    void malformedBodiesAreIgnoredRatherThanThrown() {
        assertThatCode(() -> {
            deliver("");
            deliver(":");
            deliver("7:");
            deliver(":3");
            deliver("not-a-pair");
            deliver("seven:three");
            deliver("7:3:9");
        }).doesNotThrowAnyException();

        verifyNoInteractions(repo);
    }
}
