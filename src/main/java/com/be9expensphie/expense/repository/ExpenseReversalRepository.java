package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.enums.ReversalState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExpenseReversalRepository extends JpaRepository<ExpenseReversal, String> {

    Optional<ExpenseReversal> findByExpenseIdAndState(Long expenseId, ReversalState state);

    Optional<ExpenseReversal> findFirstByExpenseIdOrderByRequestedAtDesc(Long expenseId);

    List<ExpenseReversal> findByStateAndLastRequestedAtBefore(ReversalState state, Instant before);
}
