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

@Component
@RequiredArgsConstructor
@Slf4j
public class HouseholdMemberEventConsumer {
    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;
    private final HouseholdMembershipCache membershipCache;

    @KafkaListener(topics = "household-member-events", containerFactory = "householdMemberEventKafkaListenerContainerFactory")
    public void consume(HouseholdMemberEvent event){
        if ("MEMBER_JOINED".equals(event.getEventType())) {
            // A read that arrived before this event cached a negative result.
            // Drop it now, or the new member is refused until the TTL expires.
            membershipCache.invalidate(event.getUserId(), event.getHouseholdId());
            householdMemberSummaryRepo.findById(event.getMemberId()).ifPresentOrElse(
                    existing -> log.info("HouseholdMemberSummary already exists for memberId={}", event.getMemberId()),
                    () -> {
                        householdMemberSummaryRepo.save(HouseholdMemberSummary.builder()
                                .memberId(event.getMemberId())
                                .householdId(event.getHouseholdId())
                                .userId(event.getUserId())
                                .fullName(event.getFullName())
                                .role(HouseholdRole.valueOf(event.getRole()))
                                .build());
                        log.info("Saved HouseholdMemberSummary for memberId={}, householdId={}", event.getMemberId(), event.getHouseholdId());
                    }
            );
        }
}
}
