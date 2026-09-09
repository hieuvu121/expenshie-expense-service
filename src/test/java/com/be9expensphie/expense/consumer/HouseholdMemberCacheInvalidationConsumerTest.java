package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.service.HouseholdMembershipCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HouseholdMemberCacheInvalidationConsumerTest {

    private static final long HOUSEHOLD_ID = 3L;
    private static final long USER_ID = 7L;

    @Mock
    private HouseholdMembershipCache membershipCache;

    @InjectMocks
    private HouseholdMemberCacheInvalidationConsumer consumer;

    private static HouseholdMemberEvent event(String type, Long userId, Long householdId) {
        return HouseholdMemberEvent.builder()
                .memberId(11L)
                .householdId(householdId)
                .userId(userId)
                .eventType(type)
                .build();
    }

    @Test
    void memberLeftInvalidatesThisReplicasEntry() {
        consumer.consume(event("MEMBER_LEFT", USER_ID, HOUSEHOLD_ID));

        verify(membershipCache).invalidate(USER_ID, HOUSEHOLD_ID);
    }

    /** Any event can change the cached boolean, so all of them invalidate. */
    @Test
    void otherEventTypesInvalidateToo() {
        consumer.consume(event("MEMBER_JOINED", USER_ID, HOUSEHOLD_ID));
        consumer.consume(event("ROLE_CHANGED", USER_ID, HOUSEHOLD_ID));

        verify(membershipCache, org.mockito.Mockito.times(2)).invalidate(USER_ID, HOUSEHOLD_ID);
    }

    /** This listener writes nothing, so a malformed event is dropped, not retried. */
    @Test
    void eventsMissingIdentifiersAreIgnored() {
        assertThatCode(() -> {
            consumer.consume(event("MEMBER_LEFT", null, HOUSEHOLD_ID));
            consumer.consume(event("MEMBER_LEFT", USER_ID, null));
            consumer.consume(null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(membershipCache);
    }
}
