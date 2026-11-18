package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.entity.ExpenseEntity.Split;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Expenses in a given state, for tests.
 *
 * ExpenseEntity has no builder or setters, so the only way to make one is
 * createExpense -- which always yields PENDING or APPROVED with no id. Tests of
 * later transitions need an expense that is already REVERSING, REJECTED and
 * so on, as if loaded from the database. This forces id and status by
 * reflection on an otherwise valid expense. Test code only: production code
 * reaches those states through the aggregate's own methods.
 *
 * The expense: household 3, 100.00 AUD split EQUAL 50/50 between members 11
 * and 12, created by 11, reviewed by 12.
 */
public final class ExpenseFixtures {

    public static final long HOUSEHOLD_ID = 3L;
    public static final long CREATOR_ID = 11L;
    public static final long ADMIN_ID = 12L;

    private ExpenseFixtures() {
    }

    /* A valid expense in the given status; id may be null for one that is about to be persisted. */
    public static ExpenseEntity expense(Long id, ExpenseStatus status) {
        ExpenseEntity e = ExpenseEntity.createExpense(HOUSEHOLD_ID, new BigDecimal("100.00"), "AUD",
                LocalDate.of(2026, 9, 1), "Food", null, Method.EQUAL,
                List.of(new Split(CREATOR_ID, new BigDecimal("50.00")),
                        new Split(ADMIN_ID, new BigDecimal("50.00"))),
                CREATOR_ID, ADMIN_ID, false);
        ReflectionTestUtils.setField(e, "id", id);
        ReflectionTestUtils.setField(e, "status", status);
        return e;
    }
}
