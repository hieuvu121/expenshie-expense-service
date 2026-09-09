package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.HouseholdRole;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.be9expensphie.expense.service.HouseholdMembershipCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Owns the local HouseholdMemberSummary projection.
 *
 * Runs under the shared expense-service-group, so exactly one replica handles
 * each event — which is what the database write wants and what cache
 * invalidation does not. HouseholdMemberCacheInvalidationConsumer covers the
 * other replicas.
 *
 * No @Transactional: each save() below is a single row and commits under the
 * repository's own transaction, so it has landed before invalidate() runs.
 * Wrapping the method would put the invalidate inside the transaction, where a
 * concurrent read still sees the old committed row and re-caches it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdMemberEventConsumer {

    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;
    private final HouseholdMembershipCache membershipCache;

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
        membershipCache.invalidate(event.getUserId(), event.getHouseholdId());
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
        // this replica cached in the meantime.
        membershipCache.invalidate(event.getUserId(), event.getHouseholdId());
    }
}
