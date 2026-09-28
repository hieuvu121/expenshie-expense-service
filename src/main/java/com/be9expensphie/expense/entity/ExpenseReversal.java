package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ReversalState;
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
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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
}
