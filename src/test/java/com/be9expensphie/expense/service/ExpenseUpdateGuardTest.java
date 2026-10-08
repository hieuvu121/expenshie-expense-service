package com.be9expensphie.expense.service;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.common.enums.HouseholdRole;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.repository.ExpenseRepository;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Editing an approved expense silently diverged from the settlements derived
 * from it at approval time.
 *
 * Approve $100 split 50/50 and settlement_db records a $50 debt. Edit the
 * amount to $300 and expense_db says $300 while settlement_db still says $50 --
 * permanently, because no event was published. Publishing one would not have
 * helped either: createSettlementsForExpense guards on
 * existsByExpenseIdAndFromMemberId and would have skipped the correction.
 *
 * An approved expense is corrected by reversing it and posting a new one.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseUpdateGuardTest {

    private static final long HOUSEHOLD_ID = 3L;
    private static final long EXPENSE_ID = 42L;
    private static final long USER_ID = 7L;

    @Mock private ExpenseRepository expenseRepo;
    @Mock private HouseholdMemberSummaryRepository householdMemberSummaryRepo;

    private ExpenseService service;

    @BeforeEach
    void setUp() {
        /* Only the two collaborators updateExpense reaches before the guard. */
        service = new ExpenseService(expenseRepo, householdMemberSummaryRepo,
                null, null, null, null, null, null);

        when(householdMemberSummaryRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(Optional.of(HouseholdMemberSummary.builder()
                        .memberId(11L)
                        .householdId(HOUSEHOLD_ID)
                        .userId(USER_ID)
                        .fullName("Dana")
                        .role(HouseholdRole.ROLE_ADMIN)
                        .build()));
    }

    private void expenseIs(ExpenseStatus status) {
        when(expenseRepo.findByIdAndHouseholdId(EXPENSE_ID, HOUSEHOLD_ID))
                .thenReturn(Optional.of(ExpenseEntity.builder()
                        .id(EXPENSE_ID)
                        .householdId(HOUSEHOLD_ID)
                        .amount(new BigDecimal("100.00"))
                        .currency("AUD")
                        .method(Method.EQUAL)
                        .date(LocalDate.of(2026, 9, 1))
                        .status(status)
                        .createdByMemberId(11L)
                        .build()));
    }

    private static CreateExpenseRequestDTO amountChange() {
        return CreateExpenseRequestDTO.builder().amount(new BigDecimal("300.00")).build();
    }

    @Test
    void anApprovedExpenseCannotBeEdited() {
        expenseIs(ExpenseStatus.APPROVED);

        assertThatThrownBy(() -> service.updateExpense(HOUSEHOLD_ID, EXPENSE_ID, amountChange(), USER_ID))
                .hasMessageContaining("Only a pending expense can be edited");

        verify(expenseRepo, never()).save(any());
    }

    @Test
    void aRejectedExpenseCannotBeEdited() {
        expenseIs(ExpenseStatus.REJECTED);

        assertThatThrownBy(() -> service.updateExpense(HOUSEHOLD_ID, EXPENSE_ID, amountChange(), USER_ID))
                .hasMessageContaining("Only a pending expense can be edited");

        verify(expenseRepo, never()).save(any());
    }
}
