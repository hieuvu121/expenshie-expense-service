package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "expense",
    indexes = {
        @Index(name = "idx_created_by_member_id", columnList = "created_by_member_id"),
        @Index(name = "idx_expense_list", columnList = "household_id,status,id"),
        @Index(name = "idx_expense_date_range", columnList = "household_id,status,date"),
        @Index(name = "idx_reviewed_by_member_id", columnList = "reviewed_by_member_id")
    }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString(exclude = "splitDetails")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExpenseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseStatus status;

    @Column(nullable = true)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Method method;

    @Column(name = "created_by_member_id", nullable = false)
    private Long createdByMemberId;

    @Column(name = "reviewed_by_member_id", nullable = false)
    private Long reviewedByMemberId;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ExpenseSplitDetailsEntity> splitDetails = new ArrayList<>();
}
