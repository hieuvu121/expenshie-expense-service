package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The saga's own state machine: AWAITING_SETTLEMENT ends exactly once, as
 * COMPLETED, CANCELLED or FAILED.
 */
class ExpenseReversalTest {

    private static ExpenseReversal saga(ReversalState state) {
        return ExpenseReversal.builder()
                .sagaId("saga-1").expenseId(42L).householdId(3L)
                .state(state).requestedAt(Instant.now()).lastRequestedAt(Instant.now())
                .attempts(1)
                .build();
    }

    @Test
    void completeEndsTheSaga() {
        ExpenseReversal r = saga(ReversalState.AWAITING_SETTLEMENT);

        r.complete();

        assertThat(r.getState()).isEqualTo(ReversalState.COMPLETED);
        assertThat(r.getFailureReason()).isNull();
    }

    @Test
    void cancelKeepsSettlementsReason() {
        ExpenseReversal r = saga(ReversalState.AWAITING_SETTLEMENT);

        r.cancel("already settled");

        assertThat(r.getState()).isEqualTo(ReversalState.CANCELLED);
        assertThat(r.getFailureReason()).isEqualTo("already settled");
    }

    /* A finished saga must not be finished a second way by a late or duplicate reply. */
    @ParameterizedTest
    @EnumSource(value = ReversalState.class, names = "AWAITING_SETTLEMENT", mode = EnumSource.Mode.EXCLUDE)
    void aFinishedSagaCannotBeFinishedAgain(ReversalState state) {
        ExpenseReversal r = saga(state);

        assertThatThrownBy(r::complete).isInstanceOf(InvalidExpenseStatusException.class);
        assertThatThrownBy(() -> r.cancel("x")).isInstanceOf(InvalidExpenseStatusException.class);
        assertThatThrownBy(() -> r.markFailed("x")).isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(r.getState()).isEqualTo(state);
    }

    /* An overlong reason would fail the UPDATE that records the failure. */
    @Test
    void anOverlongReasonIsCutToFitTheColumn() {
        ExpenseReversal failed = saga(ReversalState.AWAITING_SETTLEMENT);
        ExpenseReversal cancelled = saga(ReversalState.AWAITING_SETTLEMENT);
        String longReason = "x".repeat(2_000);

        failed.markFailed(longReason);
        cancelled.cancel(longReason);

        assertThat(failed.getFailureReason())
                .hasSize(ExpenseReversal.FAILURE_REASON_MAX)
                .endsWith("...");
        assertThat(cancelled.getFailureReason()).hasSize(ExpenseReversal.FAILURE_REASON_MAX);
    }

    @Test
    void aReasonThatFitsIsKeptWhole() {
        ExpenseReversal r = saga(ReversalState.AWAITING_SETTLEMENT);
        String exact = "y".repeat(ExpenseReversal.FAILURE_REASON_MAX);

        r.markFailed(exact);

        assertThat(r.getFailureReason()).isEqualTo(exact);
    }

    /* The count and the timestamp move together: the sweep selects by one and gives up by the other. */
    @Test
    void reRequestingCountsTheAttemptAndRestartsTheClock() {
        ExpenseReversal r = saga(ReversalState.AWAITING_SETTLEMENT);
        Instant now = Instant.parse("2026-11-18T06:00:00Z");

        r.recordReRequest(now);

        assertThat(r.getAttempts()).isEqualTo(2);
        assertThat(r.getLastRequestedAt()).isEqualTo(now);
        assertThat(r.getState()).isEqualTo(ReversalState.AWAITING_SETTLEMENT);
    }

    @ParameterizedTest
    @EnumSource(value = ReversalState.class, names = "AWAITING_SETTLEMENT", mode = EnumSource.Mode.EXCLUDE)
    void aFinishedSagaIsNeverReRequested(ReversalState state) {
        ExpenseReversal r = saga(state);

        assertThatThrownBy(() -> r.recordReRequest(Instant.now()))
                .isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(r.getAttempts()).isEqualTo(1);
    }
}
