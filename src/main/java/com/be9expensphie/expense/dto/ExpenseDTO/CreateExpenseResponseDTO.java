package com.be9expensphie.expense.dto.ExpenseDTO;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
