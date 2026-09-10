package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Pins the positional contract between the native queries' select lists and
 * CreateExpenseResponseDTO's constructor.
 *
 * The list queries are native so they can carry a FORCE INDEX, and rows are
 * read as Object[] to avoid a JDK proxy per row. Nothing validates that binding
 * at startup - the JPQL constructor expression used to - so swapping two
 * columns in the SQL, or two fields in the DTO, would silently serve wrong
 * data. These tests fail instead.
 *
 * java.lang.reflect.Method is written out in full below: Method here is the
 * expense enum.
 */
class ExpenseRepositoryMappingTest {

    private static final List<String> EXPECTED_COLUMN_ORDER = List.of(
            "createdBy", "id", "amount", "date",
            "category", "description", "status", "method", "currency");

    private static String sqlOf(String methodName) {
        for (java.lang.reflect.Method m : ExpenseRepository.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                Query q = m.getAnnotation(Query.class);
                if (q != null) {
                    return q.value();
                }
            }
        }
        throw new AssertionError("no @Query found on " + methodName);
    }

    private static List<String> aliasesOf(String sql) {
        String lower = sql.toLowerCase();
        String selectList = sql.substring(lower.indexOf("select") + "select".length(),
                                          lower.indexOf("from"));
        return Arrays.stream(selectList.split(","))
                .map(String::trim)
                .filter(s -> s.toLowerCase().contains(" as "))
                .map(s -> s.substring(s.toLowerCase().lastIndexOf(" as ") + 4).trim())
                .toList();
    }

    @Test
    void findPageRowsSelectsColumnsInDtoConstructorOrder() {
        assertThat(aliasesOf(sqlOf("findPageRows"))).isEqualTo(EXPECTED_COLUMN_ORDER);
    }

    @Test
    void findPageRowsByStatusSelectsColumnsInDtoConstructorOrder() {
        assertThat(aliasesOf(sqlOf("findPageRowsByStatus"))).isEqualTo(EXPECTED_COLUMN_ORDER);
    }

    /** Each Object[] index must land in the matching DTO field. */
    @Test
    void rowsMapPositionallyOntoTheDto() {
        Object[] row = {
                "Dana", 42L, new BigDecimal("431.00"), java.sql.Date.valueOf("2026-09-10"),
                "Groceries", "perf seed 200", "APPROVED", "EQUAL", "VND"
        };
        ExpenseRepository repo = mock(ExpenseRepository.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        /* singletonList, not List.of: List.of(E...) would spread the Object[] into nine elements. */
        doReturn(Collections.singletonList(row)).when(repo).findPageRows(anyLong(), anyLong(), anyInt());

        CreateExpenseResponseDTO dto = repo.findPage(1L, Long.MAX_VALUE, 10).get(0);

        assertThat(dto.getCreatedBy()).isEqualTo("Dana");
        assertThat(dto.getId()).isEqualTo(42L);
        assertThat(dto.getAmount()).isEqualByComparingTo("431.00");
        assertThat(dto.getDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(dto.getCategory()).isEqualTo("Groceries");
        assertThat(dto.getDescription()).isEqualTo("perf seed 200");
        assertThat(dto.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
        assertThat(dto.getMethod()).isEqualTo(Method.EQUAL);
        assertThat(dto.getCurrency()).isEqualTo("VND");
    }
}
