package com.be9expensphie.expense.validation;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class ExpenseValidation {

    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;

    public void validateExpense(CreateExpenseRequestDTO request, Long householdId) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Amount must be greater than 0");
        }
        if (request.getCategory() == null || request.getCategory().isBlank()) {
            throw new RuntimeException("Category must be filled");
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new RuntimeException("Currency must be filled");
        }
        if (request.getMethod() == null) {
            throw new RuntimeException("Method must be filled");
        }

        List<SplitRequestDTO> splits = request.getSplits();
        if (splits == null || splits.isEmpty()) {
            throw new RuntimeException("Must have at least 1 splits");
        }

        Set<Long> validMemberIds = new HashSet<>();
        householdMemberSummaryRepo.findByHouseholdId(householdId)
                .forEach(m -> validMemberIds.add(m.getMemberId()));

        BigDecimal total = BigDecimal.ZERO;
        for (SplitRequestDTO split : splits) {
            if (split.getMemberId() == null || !validMemberIds.contains(split.getMemberId())) {
                throw new RuntimeException("Member not in household or invalid!");
            }
            if (split.getAmount() == null || split.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new RuntimeException("Split amount must be >= 0");
            }
            total = total.add(split.getAmount());
        }

        if (total.compareTo(request.getAmount()) != 0) {
            throw new RuntimeException("Sum of split amounts must equal total amount");
        }

        if (request.getMethod() == Method.EQUAL) {
            BigDecimal first = splits.get(0).getAmount();
            for (SplitRequestDTO split : splits) {
                if (split.getAmount().compareTo(first) != 0) {
                    throw new RuntimeException("Splits of each member should equal");
                }
            }
        }
    }
}
