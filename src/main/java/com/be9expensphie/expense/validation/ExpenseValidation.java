package com.be9expensphie.expense.validation;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * The one creation rule the expense cannot check by itself: every split
 * belongs to a current member of the household. That needs the repository,
 * and members are another aggregate.
 *
 * Everything about the expense's own data -- amount, splits summing to it,
 * EQUAL splits, duplicates -- is enforced by ExpenseEntity.createExpense.
 */
@RequiredArgsConstructor
@Component
public class ExpenseValidation {

    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;

    public void validateExpense(CreateExpenseRequestDTO request, Long householdId) {
        /* No splits at all is the aggregate's to reject. */
        if (request.getSplits() == null || request.getSplits().isEmpty()) {
            return;
        }

        Set<Long> validMemberIds = new HashSet<>();
        // Filtered: a split may not be assigned to a member who has left.
        householdMemberSummaryRepo.findByHouseholdIdAndRemovedAtIsNull(householdId)
                .forEach(m -> validMemberIds.add(m.getMemberId()));

        for (SplitRequestDTO split : request.getSplits()) {
            if (split == null || split.getMemberId() == null) {
                continue;
            }
            if (!validMemberIds.contains(split.getMemberId())) {
                throw new RuntimeException("Member not in household or invalid!");
            }
        }
    }
}
