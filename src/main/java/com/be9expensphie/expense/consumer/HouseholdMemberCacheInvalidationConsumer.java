package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.service.HouseholdMembershipCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Drops this replica's cached membership answer for the affected user.
 *
 * Deliberately separate from HouseholdMemberEventConsumer, which owns the
 * database write. That one runs under the shared expense-service-group, so
 * only one replica sees each event; the other replicas would keep serving a
 * stale positive from their own Caffeine maps until the TTL expired. This
 * listener runs under a per-JVM group id (see
 * KafkaConsumerConfig.householdMemberCacheInvalidationConsumerFactory), so
 * every replica receives every event.
 *
 * It must stay write-free. Running on every replica means any persistence
 * here would be executed N times over.
 *
 * NOT airtight, and not claimed to be. This group is independent of
 * expense-service-group, so a replica can invalidate before the replica
 * owning the write has stamped removedAt. A read landing in that gap
 * re-populates from a row that still looks active and re-caches the positive.
 * The window is small — one Kafka delivery plus one row update — where it used
 * to be the full TTL on every replica, and the worst case is unchanged: stale
 * until app.membership-cache-ttl-seconds expires. Closing it completely needs
 * the write to be visible before any invalidation runs, i.e. a second event
 * published after commit rather than a second listener on the first one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdMemberCacheInvalidationConsumer {

    private final HouseholdMembershipCache membershipCache;

    @KafkaListener(topics = "household-member-events",
                   containerFactory = "householdMemberCacheInvalidationKafkaListenerContainerFactory")
    public void consume(HouseholdMemberEvent event) {
        if (event == null || event.getUserId() == null || event.getHouseholdId() == null) {
            log.warn("Ignoring HouseholdMemberEvent with no userId/householdId");
            return;
        }
        // Every type, not just MEMBER_LEFT: the cached value is a plain
        // boolean, so any event that could change it is worth one map removal.
        // Invalidation is idempotent and costs nothing on a miss.
        membershipCache.invalidate(event.getUserId(), event.getHouseholdId());
        log.debug("Invalidated membership cache: userId={}, householdId={}, type={}",
                event.getUserId(), event.getHouseholdId(), event.getEventType());
    }
}
