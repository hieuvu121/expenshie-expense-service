package com.be9expensphie.expense.dto.ExpenseDTO;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class CreateExpenseResponseDTO {
    private String createdBy;
    private Long id;
    private BigDecimal amount;
    private LocalDate date;
    private String category;
    private String description;
    private ExpenseStatus status;
    private Method method;
    private String currency;
}
