package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.entity.ExpenseEntity.Split;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reversal state machine owned by the aggregate.
 *
 * APPROVED -> REVERSING -> REVERSED, with REVERSING -> APPROVED as the
 * compensation. Every other transition must be refused and leave the status
 * untouched, because the service no longer checks anything itself.
 *
 * Also covers createExpense and updateExpense: a new expense and an edited
 * one must satisfy the same invariants.
 *
 * Plain JUnit: the rules live on the entity, so nothing needs mocking.
 */
class ExpenseEntityTest {

    private static ExpenseEntity expense(ExpenseStatus status) {
        return ExpenseFixtures.expense(42L, status);
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

    /* ---------- createExpense ---------- */

    private static final Long HOUSEHOLD = 3L;
    private static final Long CREATOR = 11L;
    private static final Long ADMIN = 12L;

    private static BigDecimal money(String v) {
        return new BigDecimal(v);
    }

    private static ExpenseEntity create(BigDecimal amount, Method method, List<Split> splits, boolean byAdmin) {
        return ExpenseEntity.createExpense(HOUSEHOLD, amount, "AUD", LocalDate.of(2026, 9, 1),
                "Food", "dinner", method, splits, CREATOR, ADMIN, byAdmin);
    }

    private static List<Split> halves() {
        return List.of(new Split(11L, money("50.00")), new Split(12L, money("50.00")));
    }

    @Test
    void aMemberExpenseIsBornPending() {
        ExpenseEntity e = create(money("100.00"), Method.EQUAL, halves(), false);

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.PENDING);
        assertThat(e.getCreatedByMemberId()).isEqualTo(CREATOR);
        assertThat(e.getReviewedByMemberId()).isEqualTo(ADMIN);
        assertThat(e.getHouseholdId()).isEqualTo(HOUSEHOLD);
    }

    @Test
    void anAdminExpenseIsBornApproved() {
        ExpenseEntity e = create(money("100.00"), Method.EQUAL, halves(), true);

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
    }

    /* The root owns its splits: each one is created by it and points back at it. */
    @Test
    void splitsAreBuiltAsChildrenOfTheExpense() {
        ExpenseEntity e = create(money("100.00"), Method.EQUAL, halves(), false);

        assertThat(e.getSplitDetails()).hasSize(2)
                .allSatisfy(s -> assertThat(s.getExpense()).isSameAs(e))
                .extracting(ExpenseSplitDetailsEntity::getMemberId)
                .containsExactly(11L, 12L);
    }

    /* compareTo, not equals: 100.0 against splits summing to 100.00 is the same money. */
    @Test
    void scaleDifferencesDoNotCountAsAMismatch() {
        ExpenseEntity e = create(money("100.0"), Method.EQUAL, halves(), false);

        assertThat(e.getAmount()).isEqualByComparingTo("100");
    }

    @Test
    void splitsMustSumToTheAmount() {
        assertThatThrownBy(() -> create(money("120.00"), Method.EQUAL, halves(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100.00")
                .hasMessageContaining("120.00");
    }

    @Test
    void equalSplitsMustActuallyBeEqual() {
        List<Split> uneven = List.of(new Split(11L, money("70.00")), new Split(12L, money("30.00")));

        assertThatThrownBy(() -> create(money("100.00"), Method.EQUAL, uneven, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EQUAL");
    }

    @Test
    void unevenSplitsAreFineForAmount() {
        List<Split> uneven = List.of(new Split(11L, money("70.00")), new Split(12L, money("30.00")));

        ExpenseEntity e = create(money("100.00"), Method.AMOUNT, uneven, false);

        assertThat(e.getSplitDetails()).hasSize(2);
    }

    /* The table's unique (expense_id, member_id) would otherwise surface as a raw constraint error. */
    @Test
    void aMemberCannotAppearInTwoSplits() {
        List<Split> twice = List.of(new Split(11L, money("50.00")), new Split(11L, money("50.00")));

        assertThatThrownBy(() -> create(money("100.00"), Method.EQUAL, twice, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11");
    }

    @Test
    void aNegativeSplitIsRefused() {
        List<Split> negative = List.of(new Split(11L, money("150.00")), new Split(12L, money("-50.00")));

        assertThatThrownBy(() -> create(money("100.00"), Method.AMOUNT, negative, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void anExpenseNeedsAtLeastOneSplit() {
        assertThatThrownBy(() -> create(money("100.00"), Method.EQUAL, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(money("100.00"), Method.EQUAL, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSplitWithoutAMemberIsRefused() {
        List<Split> noMember = List.of(new Split(null, money("100.00")));

        assertThatThrownBy(() -> create(money("100.00"), Method.EQUAL, noMember, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void theAmountMustBePositive(String amount) {
        assertThatThrownBy(() -> create(money(amount), Method.EQUAL, halves(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than 0");
    }

    @Test
    void currencyCategoryMethodAndDateAreRequired() {
        assertThatThrownBy(() -> ExpenseEntity.createExpense(HOUSEHOLD, money("100.00"), " ",
                LocalDate.of(2026, 9, 1), "Food", null, Method.EQUAL, halves(), CREATOR, ADMIN, false))
                .hasMessageContaining("Currency");
        assertThatThrownBy(() -> ExpenseEntity.createExpense(HOUSEHOLD, money("100.00"), "AUD",
                LocalDate.of(2026, 9, 1), null, null, Method.EQUAL, halves(), CREATOR, ADMIN, false))
                .hasMessageContaining("Category");
        assertThatThrownBy(() -> ExpenseEntity.createExpense(HOUSEHOLD, money("100.00"), "AUD",
                LocalDate.of(2026, 9, 1), "Food", null, null, halves(), CREATOR, ADMIN, false))
                .hasMessageContaining("Method");
        assertThatThrownBy(() -> ExpenseEntity.createExpense(HOUSEHOLD, money("100.00"), "AUD",
                null, "Food", null, Method.EQUAL, halves(), CREATOR, ADMIN, false))
                .hasMessageContaining("Date");
    }

    /* ---------- updateExpense ---------- */

    private static ExpenseEntity pendingHundred() {
        return create(money("100.00"), Method.EQUAL, halves(), false);
    }

    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void onlyAPendingExpenseCanBeEdited(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(() -> e.updateExpense(money("200.00"), null, null, null, null, null, null))
                .isInstanceOf(InvalidExpenseStatusException.class);
        assertThat(e.getAmount()).isEqualByComparingTo("100.00");
    }

    /* The bug this replaces: the amount moved and the splits stayed behind. */
    @Test
    void changingOnlyTheAmountMustStillMatchTheSplits() {
        ExpenseEntity e = pendingHundred();

        assertThatThrownBy(() -> e.updateExpense(money("120.00"), null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120.00");
        assertThat(e.getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void amountAndSplitsCanChangeTogether() {
        ExpenseEntity e = pendingHundred();

        e.updateExpense(money("120.00"), null, null, null, null, null,
                List.of(new Split(11L, money("60.00")), new Split(12L, money("60.00"))));

        assertThat(e.getAmount()).isEqualByComparingTo("120.00");
        assertThat(e.getSplitDetails())
                .extracting(ExpenseSplitDetailsEntity::getAmount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("60.00"));
    }

    /*
     * A member left out of the new list loses their split. The old merge kept
     * it, so sending {11: 100} for a 50/50 expense summed to 150.
     */
    @Test
    void theNewSplitListReplacesTheOldOne() {
        ExpenseEntity e = pendingHundred();
        ExpenseSplitDetailsEntity kept = e.getSplitDetails().get(0);

        e.updateExpense(null, null, null, null, null, Method.AMOUNT,
                List.of(new Split(11L, money("70.00")), new Split(13L, money("30.00"))));

        assertThat(e.getSplitDetails())
                .extracting(ExpenseSplitDetailsEntity::getMemberId)
                .containsExactlyInAnyOrder(11L, 13L);
        /* Member 11's row is updated in place, not replaced, so the unique key is never hit. */
        assertThat(e.getSplitDetails()).contains(kept);
        assertThat(kept.getAmount()).isEqualByComparingTo("70.00");
        assertThat(e.getSplitDetails()).allSatisfy(d -> assertThat(d.getExpense()).isSameAs(e));
    }

    @Test
    void nullArgumentsKeepTheCurrentValues() {
        ExpenseEntity e = pendingHundred();

        e.updateExpense(null, null, null, "Groceries", null, null, null);

        assertThat(e.getCategory()).isEqualTo("Groceries");
        assertThat(e.getAmount()).isEqualByComparingTo("100.00");
        assertThat(e.getCurrency()).isEqualTo("AUD");
        assertThat(e.getDescription()).isEqualTo("dinner");
        assertThat(e.getSplitDetails()).hasSize(2);
    }

    @Test
    void switchingToEqualChecksTheExistingSplits() {
        ExpenseEntity e = create(money("100.00"), Method.AMOUNT,
                List.of(new Split(11L, money("70.00")), new Split(12L, money("30.00"))), false);

        assertThatThrownBy(() -> e.updateExpense(null, null, null, null, null, Method.EQUAL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EQUAL");
        assertThat(e.getMethod()).isEqualTo(Method.AMOUNT);
    }

    /* ---------- approve / reject ---------- */

    @Test
    void approveMovesPendingToApproved() {
        ExpenseEntity e = expense(ExpenseStatus.PENDING);

        e.approve();

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
    }

    /* Covers approving twice: the second call finds APPROVED and is refused. */
    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void approveRefusesAnythingButPending(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(e::approve)
                .isInstanceOf(InvalidExpenseStatusException.class)
                .hasMessageContaining("approve");
        assertThat(e.getStatus()).isEqualTo(status);
    }

    @Test
    void rejectMovesPendingToRejected() {
        ExpenseEntity e = expense(ExpenseStatus.PENDING);

        e.reject();

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.REJECTED);
    }

    @ParameterizedTest
    @EnumSource(value = ExpenseStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void rejectRefusesAnythingButPending(ExpenseStatus status) {
        ExpenseEntity e = expense(status);

        assertThatThrownBy(e::reject)
                .isInstanceOf(InvalidExpenseStatusException.class)
                .hasMessageContaining("reject");
        assertThat(e.getStatus()).isEqualTo(status);
    }
}
