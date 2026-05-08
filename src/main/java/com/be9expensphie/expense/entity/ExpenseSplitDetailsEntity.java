package com.be9expensphie.expense.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "expense_split_details",
    uniqueConstraints = @UniqueConstraint(columnNames = {"expense_id", "member_id"})
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString(exclude = "expense")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExpenseSplitDetailsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(nullable = false, name = "expense_id")
    private ExpenseEntity expense;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "member_id", nullable = false)
    private Long memberId;
}
