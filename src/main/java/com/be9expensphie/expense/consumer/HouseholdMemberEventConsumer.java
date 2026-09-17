package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.HouseholdRole;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Owns the local HouseholdMemberSummary projection.
 *
 * Runs under the shared expense-service-group, so exactly one replica handles
 * each event — which is what the database write wants and what cache
 * invalidation does not. The other replicas are reached by publishing on Redis
 * after the write; MembershipInvalidationListener applies it on each of them.
 *
 * No @Transactional: each save() below is a single row and commits under the
 * repository's own transaction, so it has landed before the publish goes out.
 * Wrapping the method would put the publish inside the transaction, where a
 * concurrent read still sees the old committed row and re-caches it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdMemberEventConsumer {

    /** Also read by RedisConfig, which subscribes the listener to it. */
    public static final String MEMBERSHIP_INVALIDATED_CHANNEL = "membership-invalidated";

    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "household-member-events",
                   containerFactory = "householdMemberEventKafkaListenerContainerFactory")
    public void consume(HouseholdMemberEvent event) {
        if (event == null || event.getEventType() == null) {
            log.warn("Ignoring HouseholdMemberEvent with no eventType");
            return;
        }
        switch (event.getEventType()) {
            case "MEMBER_JOINED" -> onMemberJoined(event);
            case "MEMBER_LEFT" -> onMemberLeft(event);
            default -> log.debug("Ignoring unhandled eventType={}", event.getEventType());
        }
    }

    private void onMemberJoined(HouseholdMemberEvent event) {
        householdMemberSummaryRepo.findById(event.getMemberId()).ifPresentOrElse(
                existing -> {
                    // A re-join lands here with removedAt still set by the
                    // earlier MEMBER_LEFT. The previous "already exists,
                    // nothing to do" branch would leave that tombstone in
                    // place and lock the returning member out permanently.
                    existing.setRemovedAt(null);
                    existing.setFullName(event.getFullName());
                    existing.setRole(HouseholdRole.valueOf(event.getRole()));
                    householdMemberSummaryRepo.save(existing);
                    log.info("Reinstated HouseholdMemberSummary for memberId={}", event.getMemberId());
                },
                () -> {
                    householdMemberSummaryRepo.save(HouseholdMemberSummary.builder()
                            .memberId(event.getMemberId())
                            .householdId(event.getHouseholdId())
                            .userId(event.getUserId())
                            .fullName(event.getFullName())
                            .role(HouseholdRole.valueOf(event.getRole()))
                            .build());
                    log.info("Saved HouseholdMemberSummary for memberId={}, householdId={}",
                            event.getMemberId(), event.getHouseholdId());
                }
        );
        // After the write, not before. The old code invalidated first: a read
        // arriving between that invalidate and the commit re-cached the
        // negative and locked the new member out for the full TTL anyway.
        publishInvalidation(event.getUserId(), event.getHouseholdId());
    }

    private void onMemberLeft(HouseholdMemberEvent event) {
        householdMemberSummaryRepo.findById(event.getMemberId()).ifPresentOrElse(
                existing -> {
                    if (existing.getRemovedAt() == null) {
                        existing.setRemovedAt(Instant.now());
                        householdMemberSummaryRepo.save(existing);
                        log.info("Retired HouseholdMemberSummary: memberId={}, householdId={}",
                                event.getMemberId(), event.getHouseholdId());
                    }
                },
                () -> log.warn("MEMBER_LEFT for unknown memberId={} — nothing to retire",
                        event.getMemberId())
        );
        // Outside the ifPresentOrElse on purpose: a redelivery, or an event for
        // an already-tombstoned row, must still drop whatever positive entry
        // the replicas cached in the meantime.
        publishInvalidation(event.getUserId(), event.getHouseholdId());
    }

    /**
     * Fans the invalidation out to every replica, this one included.
     *
     * Replaces HouseholdMemberCacheInvalidationConsumer, a second Kafka
     * listener on a UUID group id. That group was independent of this one, so
     * it could invalidate before this consumer had committed the row and a read
     * in the gap re-cached the stale positive. Publishing here is the
     * post-commit ordering that race needed, and it stops leaving a dead
     * consumer group in the broker after every restart.
     *
     * Swallows failures on purpose: the row is already committed, so throwing
     * would make Kafka redeliver a completed write. Replicas that miss the
     * message fall back to app.membership-cache-ttl-seconds, exactly as they
     * did when the broadcast listener was unhealthy.
     */
    private void publishInvalidation(Long userId, Long householdId) {
        try {
            redisTemplate.convertAndSend(MEMBERSHIP_INVALIDATED_CHANNEL, userId + ":" + householdId);
        } catch (Exception e) {
            log.warn("Could not publish membership invalidation for userId={}, householdId={}",
                    userId, householdId, e);
        }
    }
}
