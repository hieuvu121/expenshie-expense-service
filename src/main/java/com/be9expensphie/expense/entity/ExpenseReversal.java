package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One reversal attempt: the saga's durable state on this side.
 *
 * lastRequestedAt and attempts drive the re-request sweep, not a compensation
 * clock. See ReversalReRequestSweep for why silence must never roll an expense
 * back.
 */
@Entity
@Table(name = "expense_reversal", indexes = {
        @Index(name = "idx_reversal_state_requested", columnList = "state,last_requested_at"),
        @Index(name = "idx_reversal_expense", columnList = "expense_id")
})
/*
 * No setters: state, attempts and the failure reason only change through the
 * methods below. The builder stays because a saga is created in one piece by
 * ExpenseReversalService.requestReversal.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ExpenseReversal {

    @Id
    @Column(name = "saga_id", length = 36)
    private String sagaId;

    @Column(name = "expense_id", nullable = false)
    private Long expenseId;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "requested_by_member_id")
    private Long requestedByMemberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(32)")
    private ReversalState state;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "last_requested_at", nullable = false)
    private Instant lastRequestedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /*
     * The re-request sweep and the reply consumer both write this row, from
     * different threads, while the saga is open. Without a version the later
     * commit silently overwrites the earlier one -- a sweep committing after
     * the reply would put a COMPLETED saga back to AWAITING_SETTLEMENT. See
     * ExpenseReversalConcurrencyTest.
     *
     * NOT NULL DEFAULT 0 for the same reason as ExpenseEntity.version: rows
     * written before this column existed get 0, not NULL.
     */
    @Version
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    static final int FAILURE_REASON_MAX = 500;

    /*
     * The sweep asking settlement-service again after a silence. Only an open
     * saga is re-requested, and the count and the timestamp always move
     * together -- the sweep picks sagas by lastRequestedAt and gives up by
     * attempts.
     */
    public void recordReRequest(Instant now){
        checkState(ReversalState.AWAITING_SETTLEMENT, "re-request");
        this.attempts++;
        this.lastRequestedAt = now;
    }

    /* Settlement-service accepted: the debts are voided and the saga is done. */
    public void complete(){
        checkState(ReversalState.AWAITING_SETTLEMENT, "complete");
        this.state = ReversalState.COMPLETED;
    }

    /* Settlement-service refused; its reason is why nothing was voided. */
    public void cancel(String reason){
        checkState(ReversalState.AWAITING_SETTLEMENT, "cancel");
        this.state = ReversalState.CANCELLED;
        this.failureReason = fitColumn(reason);
    }

    public void markFailed(String reason){
        checkState(ReversalState.AWAITING_SETTLEMENT, "mark failed");
        this.state = ReversalState.FAILED;
        this.failureReason = fitColumn(reason);
    }

    /*
     * failure_reason is varchar(500). The text is built from settlement-service's
     * reason and a list of settlement ids, neither of which has a bound, and an
     * overlong value fails the UPDATE -- losing the very state change that was
     * meant to record what went wrong. Cut it instead, and say so.
     */
    private static String fitColumn(String reason){
        if (reason == null || reason.length() <= FAILURE_REASON_MAX) {
            return reason;
        }
        String marker = "...";
        return reason.substring(0, FAILURE_REASON_MAX - marker.length()) + marker;
    }

    private void checkState(ReversalState required, String action){
        if(this.state != required){
            throw new InvalidExpenseStatusException(String.format(
                    "Cannot %s saga %s: state is %s, expected %s",
                    action, sagaId, state, required));
        }
    }
}
