package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
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

    // The list path projects straight into the response DTO instead of loading
    // ExpenseEntity. Returning entities made Hibernate register each row in the
    // persistence context, allocate a dirty-check snapshot and a lazy
    // splitDetails collection wrapper — all discarded immediately, since the
    // DTO carries scalars only.
    //
    // The left join also folds in the creator name, replacing the separate
    // findAllById() batch. One statement per page instead of two.
    //
    // ORDER MATTERS: these constructor expressions bind positionally to
    // CreateExpenseResponseDTO's @AllArgsConstructor, i.e. field declaration
    // order (createdBy, id, amount, date, category, description, status,
    // method, currency). Hibernate validates arity and types at startup, so a
    // field added or retyped fails fast — but swapping two String fields would
    // not. Keep this list in sync with the DTO.
    @Query("""
           select new com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO(
               coalesce(m.fullName, 'Unknown'), e.id, e.amount, e.date, e.category,
               e.description, e.status, e.method, e.currency)
           from ExpenseEntity e
           left join HouseholdMemberSummary m on m.memberId = e.createdByMemberId
           where e.householdId = :householdId and e.id < :cursor
           order by e.id desc
           """)
    List<CreateExpenseResponseDTO> findPage(@Param("householdId") Long householdId,
                                            @Param("cursor") Long cursor,
                                            Pageable pageable);

    @Query("""
           select new com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO(
               coalesce(m.fullName, 'Unknown'), e.id, e.amount, e.date, e.category,
               e.description, e.status, e.method, e.currency)
           from ExpenseEntity e
           left join HouseholdMemberSummary m on m.memberId = e.createdByMemberId
           where e.householdId = :householdId and e.status = :status and e.id < :cursor
           order by e.id desc
           """)
    List<CreateExpenseResponseDTO> findPageByStatus(@Param("householdId") Long householdId,
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
