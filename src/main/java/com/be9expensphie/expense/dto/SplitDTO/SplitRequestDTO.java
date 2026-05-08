package com.be9expensphie.expense.dto.SplitDTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@Builder
public class SplitRequestDTO {
    private Long memberId;
    private BigDecimal amount;
}
