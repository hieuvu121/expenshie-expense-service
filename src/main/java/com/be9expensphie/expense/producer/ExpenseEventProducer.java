package com.be9expensphie.expense.producer;

import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.common.event.WebSocketEvent;
import com.be9expensphie.expense.entity.ExpenseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the events a state change produces. Does not send them.
 *
 * Sending moved to OutboxPublisher. This class used to call kafkaTemplate.send
 * from inside the caller's open transaction, so a rollback after that point --
 * a Redis eviction failing in acceptExpense, say -- left settlement-service
 * holding settlements for an expense the database still had as PENDING.
 *
 * Building stays here, and stays inside the transaction, because
 * ExpenseEntity.splitDetails is a lazy @OneToMany. Read it after the commit, on
 * a detached entity, and it throws LazyInitializationException.
 */
@Component
@RequiredArgsConstructor
public class ExpenseEventProducer {

    /** Both events a single state change can produce; webSocketEvent may be null. */
    public record Outgoing(ExpenseEvent expenseEvent, WebSocketEvent webSocketEvent) {}

    public Outgoing build(ExpenseEntity expense, String eventType) {
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

        WebSocketEvent wsEvent = null;
        if ("EXPENSE_APPROVED".equals(eventType) || "EXPENSE_REJECTED".equals(eventType)) {
            String wsPayload = "{\"expenseId\":" + expense.getId()
                    + ",\"householdId\":" + expense.getHouseholdId()
                    + ",\"status\":\"" + expense.getStatus().name()
                    + "\",\"eventType\":\"" + eventType + "\"}";
            wsEvent = WebSocketEvent.builder()
                    .destination("/topic/households/" + expense.getHouseholdId() + "/expense")
                    .payload(wsPayload)
                    .build();
        }

        return new Outgoing(event, wsEvent);
    }
}
