package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.enums.ExpenseStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<ExpenseEntity, Long> {

    @Query("select e from ExpenseEntity e where e.id < :cursor and e.householdId = :householdId order by e.id desc")
    List<ExpenseEntity> findNextExpense(@Param("cursor") Long cursor,
                                       @Param("householdId") Long householdId,
                                       Pageable pageable);

    @Query("select distinct e from ExpenseEntity e left join fetch e.splitDetails where e IN :expenses")
    List<ExpenseEntity> fetchSplitDetails(@Param("expenses") List<ExpenseEntity> expenses);

    @Query("select e from ExpenseEntity e where e.householdId = :householdId and e.status = :status and e.id < :cursor order by e.id desc")
    List<ExpenseEntity> findExpenseByStatus(@Param("householdId") Long householdId,
                                           @Param("status") ExpenseStatus status,
                                           @Param("cursor") Long cursor,
                                           Pageable pageable);

    Optional<ExpenseEntity> findByIdAndHouseholdId(Long id, Long householdId);

    @Query("select e from ExpenseEntity e where e.householdId = :householdId and e.status = :status and e.date >= :start and e.date < :end")
    List<ExpenseEntity> findExpenseInRange(@Param("householdId") Long householdId,
                                          @Param("status") ExpenseStatus status,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    @Query(value = "SELECT * FROM expense e WHERE e.household_id = :householdId AND e.status = 'APPROVED' AND e.date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH)", nativeQuery = true)
    List<ExpenseEntity> findExpenseInLastMonth(@Param("householdId") Long householdId);
}
