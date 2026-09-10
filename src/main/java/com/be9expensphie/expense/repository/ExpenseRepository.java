package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<ExpenseEntity, Long> {

    /*
     * The list path projects straight into the response DTO instead of loading
     * ExpenseEntity. Returning entities made Hibernate register each row in the
     * persistence context, allocate a dirty-check snapshot and a lazy
     * splitDetails collection wrapper - all discarded immediately, since the
     * DTO carries scalars only. The left join also folds in the creator name,
     * replacing the separate findAllById() batch: one statement per page.
     *
     * NATIVE, and with FORCE INDEX, because the optimizer picks wrong here.
     * On the JPQL version MySQL chose idx_expense_list (household_id, status,
     * id) for the no-status query. status sits between the two columns that
     * query actually filters on, so `id < cursor` could not be a range
     * boundary: it degraded to an index condition over every row in the
     * household followed by a sort. Measured on a 200-expense household,
     * EXPLAIN ANALYZE read 201 rows and sorted them to return 10 (2.16ms);
     * forced onto idx_expense_cursor it is a reverse range scan reading
     * exactly 10 with no sort (0.83ms).
     *
     * The status query was wrong in its own way - it ignored idx_expense_list,
     * the index built for it, in favour of a skip scan on
     * idx_expense_date_range, also reading 201 rows and sorting.
     *
     * Dropping the misleading index does not help: the optimizer just moves to
     * the next cheap-looking lookup and still sorts (verified with IGNORE
     * INDEX). The cost model does not credit the avoided sort, so the index has
     * to be named explicitly. JPQL cannot express this - Hibernate generates
     * its own table aliases, which an optimizer hint would have to reference.
     *
     * What this buys is not the 2x on a small household: the old plan is
     * O(household size) and the new one is O(page size). At 200 expenses that
     * is 201 rows versus 10; at 10,000 it is 10,000 versus 10.
     */
    @Query(value = """
           select coalesce(m.full_name, 'Unknown') as createdBy,
                  e.id as id, e.amount as amount, e.date as date,
                  e.category as category, e.description as description,
                  e.status as status, e.method as method, e.currency as currency
           from expense e force index (idx_expense_cursor)
           left join household_member_summary m on m.member_id = e.created_by_member_id
           where e.household_id = :householdId and e.id < :cursor
           order by e.id desc
           limit :limit
           """, nativeQuery = true)
    List<Object[]> findPageRows(@Param("householdId") Long householdId,
                                  @Param("cursor") Long cursor,
                                  @Param("limit") int limit);

    @Query(value = """
           select coalesce(m.full_name, 'Unknown') as createdBy,
                  e.id as id, e.amount as amount, e.date as date,
                  e.category as category, e.description as description,
                  e.status as status, e.method as method, e.currency as currency
           from expense e force index (idx_expense_list)
           left join household_member_summary m on m.member_id = e.created_by_member_id
           where e.household_id = :householdId and e.status = :status and e.id < :cursor
           order by e.id desc
           limit :limit
           """, nativeQuery = true)
    List<Object[]> findPageRowsByStatus(@Param("householdId") Long householdId,
                                          @Param("status") String status,
                                          @Param("cursor") Long cursor,
                                          @Param("limit") int limit);

    default List<CreateExpenseResponseDTO> findPage(Long householdId, Long cursor, int limit) {
        return toDtos(findPageRows(householdId, cursor, limit));
    }

    default List<CreateExpenseResponseDTO> findPageByStatus(Long householdId, ExpenseStatus status,
                                                            Long cursor, int limit) {
        return toDtos(findPageRowsByStatus(householdId, status.name(), cursor, limit));
    }

    /*
     * Object[] rather than an interface projection. Spring Data backs a
     * projection interface with a JDK proxy per row and dispatches every getter
     * through an interceptor: at limit+1 = 11 rows that was 11 proxies and ~99
     * reflective calls per request, on the service that is already the CPU
     * constraint. Positional access plus the all-args constructor allocates
     * neither the proxies nor a builder per row.
     *
     * ORDER MATTERS: the indexes below bind to the select list in the two
     * queries above, which in turn matches CreateExpenseResponseDTO's field
     * order. Nothing validates this at startup the way a JPQL constructor
     * expression did, so ExpenseRepositoryMappingTest pins it.
     */
    private static List<CreateExpenseResponseDTO> toDtos(List<Object[]> rows) {
        List<CreateExpenseResponseDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new CreateExpenseResponseDTO(
                    (String) r[0],
                    ((Number) r[1]).longValue(),
                    (BigDecimal) r[2],
                    ((java.sql.Date) r[3]).toLocalDate(),
                    (String) r[4],
                    (String) r[5],
                    ExpenseStatus.valueOf((String) r[6]),
                    Method.valueOf((String) r[7]),
                    (String) r[8]));
        }
        return out;
    }

    Optional<ExpenseEntity> findByIdAndHouseholdId(Long id, Long householdId);

    // Read-only single-expense fetch. Same shape as findPage: the creator name
    // arrives in the join rather than a second findById, and no entity is
    // hydrated for a DTO that carries scalars only. The entity-returning
    // overload above stays for the write paths, which mutate what they load.
    @Query("""
           select new com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO(
               coalesce(m.fullName, 'Unknown'), e.id, e.amount, e.date, e.category,
               e.description, e.status, e.method, e.currency)
           from ExpenseEntity e
           left join HouseholdMemberSummary m on m.memberId = e.createdByMemberId
           where e.id = :id and e.householdId = :householdId
           """)
    Optional<CreateExpenseResponseDTO> findDtoByIdAndHouseholdId(@Param("id") Long id,
                                                                 @Param("householdId") Long householdId);

    @Query("select e from ExpenseEntity e where e.householdId = :householdId and e.status = :status and e.date >= :start and e.date < :end")
    List<ExpenseEntity> findExpenseInRange(@Param("householdId") Long householdId,
                                          @Param("status") ExpenseStatus status,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    @Query(value = "SELECT * FROM expense e WHERE e.household_id = :householdId AND e.status = 'APPROVED' AND e.date >= DATE_SUB(CURDATE(), INTERVAL 1 MONTH)", nativeQuery = true)
    List<ExpenseEntity> findExpenseInLastMonth(@Param("householdId") Long householdId);
}
