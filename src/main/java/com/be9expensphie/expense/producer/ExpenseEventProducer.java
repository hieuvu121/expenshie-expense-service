package com.be9expensphie.expense.producer;

import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.common.event.WebSocketEvent;
import com.be9expensphie.expense.entity.ExpenseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExpenseEventProducer {

    private final KafkaTemplate<String, ExpenseEvent> expenseEventKafkaTemplate;
    private final KafkaTemplate<String, WebSocketEvent> webSocketKafkaTemplate;

    public void publish(ExpenseEntity expense, String eventType) {
        List<ExpenseEvent.SplitDetail> splits = expense.getSplitDetails().stream()
                .map(s -> ExpenseEvent.SplitDetail.builder()
                        .memberId(s.getMemberId())
                        .amount(s.getAmount())
                        .build())
                .toList();

        ExpenseEvent event = ExpenseEvent.builder()
                .expenseId(expense.getId())
                .householdId(expense.getHouseholdId())
                .status(expense.getStatus().name())
                .amount(expense.getAmount())
                .currency(expense.getCurrency())
                .category(expense.getCategory())
                .description(expense.getDescription())
                .method(expense.getMethod().name())
                .date(expense.getDate())
                .createdByMemberId(expense.getCreatedByMemberId())
                .splits(splits)
                .eventType(eventType)
                .build();

        expenseEventKafkaTemplate.send("expense-events", String.valueOf(expense.getHouseholdId()), event);
        log.info("Published ExpenseEvent: expenseId={}, type={}", expense.getId(), eventType);

        if ("EXPENSE_APPROVED".equals(eventType) || "EXPENSE_REJECTED".equals(eventType)) {
            String wsPayload = "{\"expenseId\":" + expense.getId()
                    + ",\"householdId\":" + expense.getHouseholdId()
                    + ",\"status\":\"" + expense.getStatus().name()
                    + "\",\"eventType\":\"" + eventType + "\"}";
            WebSocketEvent wsEvent = WebSocketEvent.builder()
                    .destination("/topic/households/" + expense.getHouseholdId() + "/expense")
                    .payload(wsPayload)
                    .build();
            webSocketKafkaTemplate.send("websocket-events", String.valueOf(expense.getHouseholdId()), wsEvent);
            log.info("Published WebSocketEvent: expenseId={}, type={}", expense.getId(), eventType);
        }
    }
}
