package com.be9expensphie.expense.consumer;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.expense.service.ExpenseReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExpenseReversalReplyConsumer {

    private final ExpenseReversalService reversalService;

    @KafkaListener(topics = "expense-reversal-replies",
                   containerFactory = "expenseReversalDecidedKafkaListenerContainerFactory")
    public void consume(ExpenseReversalDecided decision) {
        if (decision == null || decision.getSagaId() == null || decision.getOutcome() == null) {
            log.warn("Ignoring malformed ExpenseReversalDecided");
            return;
        }
        reversalService.onDecided(decision);
    }
}
