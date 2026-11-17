package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reversal state machine owned by the aggregate.
 *
 * APPROVED -> REVERSING -> REVERSED, with REVERSING -> APPROVED as the
 * compensation. Every other transition must be refused and leave the status
 * untouched, because the service no longer checks anything itself.
 *
 * Plain JUnit: the rules live on the entity, so nothing needs mocking.
 */
class ExpenseEntityTest {

    private static ExpenseEntity expense(ExpenseStatus status) {
        return ExpenseEntity.builder().id(42L).status(status).build();
    }

    @Test
    void startReversalMovesApprovedToReversing() {
        ExpenseEntity e = expense(ExpenseStatus.APPROVED);

        e.startReversal();

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.REVERSING);
    }

    /*
     * Covers the double reverse done one after the other: a second request on
     * a REVERSING or REVERSED expense is refused.
     */
    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void startReversalRefusesAnythingButApproved(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(e::startReversal)
                .isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void completeReversalMovesReversingToReversed() {
        ExpenseEntity e = expense(ExpenseStatus.REVERSING);

        e.completeReversal();

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.REVERSED);
    }

    /*
     * The bug this was written for: completeReversal() used to require
     * REVERSED, so it refused every real completion and accepted none.
     */
    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "REVERSING", mode = EnumSource.Mode.EXCLUDE)
    void completeReversalRefusesAnythingButReversing(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(e::completeReversal)
                .isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void cancelReversalMovesReversingBackToApproved() {
        ExpenseEntity e = expense(ExpenseStatus.REVERSING);

        e.cancelReversal();

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
    }

    /* A late REFUSED must not resurrect an expense that already finished reversing. */
    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "REVERSING", mode = EnumSource.Mode.EXCLUDE)
    void cancelReversalRefusesAnythingButReversing(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(e::cancelReversal)
                .isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void refusalNamesTheExpenseAndBothStatuses() {
        ExpenseEntity e = expense(ExpenseStatus.APPROVED);

        assertThatThrownBy(e::completeReversal)
                .hasMessage("Cannot complete reversal expense 42: status is APPROVED, expected REVERSING");
    }
}
