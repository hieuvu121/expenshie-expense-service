package com.be9expensphie.expense.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Row shape of the paginated native list queries.
 *
 * The list queries have to be native so they can carry a FORCE INDEX (see
 * ExpenseRepository), and a native query cannot use a JPQL constructor
 * expression. Columns are therefore read through this projection and mapped to
 * CreateExpenseResponseDTO in the repository, which keeps the mapping in one
 * place and leaves callers with the DTO they already expect.
 *
 * status and method are read as String rather than as their enums: the JDBC
 * column is a MySQL ENUM and there is no entity mapping in play on a native
 * query, so the conversion is done explicitly at the mapping step.
 */
public interface ExpenseRow {
    String getCreatedBy();
    Long getId();
    BigDecimal getAmount();
    LocalDate getDate();
    String getCategory();
    String getDescription();
    String getStatus();
    String getMethod();
    String getCurrency();
}
