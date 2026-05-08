package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.ExpenseSplitDetailsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExpenseSplitDetailsRepository extends JpaRepository<ExpenseSplitDetailsEntity, Long> {

    Optional<ExpenseSplitDetailsEntity> findByExpenseAndMemberId(ExpenseEntity expense, Long memberId);

    @Query("select split from ExpenseSplitDetailsEntity split where split.expense = :expense")
    List<ExpenseSplitDetailsEntity> findByExpenseWithMember(@Param("expense") ExpenseEntity expense);
}
