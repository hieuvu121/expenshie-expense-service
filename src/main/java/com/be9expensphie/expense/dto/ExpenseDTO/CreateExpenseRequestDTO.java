package com.be9expensphie.expense.dto.ExpenseDTO;

import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.enums.Method;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class CreateExpenseRequestDTO {
    @NotNull
    public BigDecimal amount;
    public LocalDate date;
    @NotBlank
    public String category;
    public String description;
    public Method method;
    public String currency;
    public List<SplitRequestDTO> splits;
}
