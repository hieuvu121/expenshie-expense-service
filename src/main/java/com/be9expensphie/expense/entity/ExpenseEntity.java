package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
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
        // The unfiltered cursor page filters (household_id, id) with no status.
        // idx_expense_list puts status between them, so only its leading column
        // is usable there and MySQL cannot walk id for the ORDER BY. Irrelevant
        // at a few hundred rows; a cliff at real volume.
        @Index(name = "idx_expense_cursor", columnList = "household_id,id"),
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

    /*
     * NOT NULL DEFAULT 0 so ddl-auto=update backfills existing rows with 0.
     * A NULL version on a row that already exists makes Hibernate treat the
     * entity as unsaved, so merge() would try to INSERT it again.
     */
    @Version
    @Column(nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private String category;

    /*
     * varchar, not a native MySQL ENUM.
     *
     * Hibernate generates enum('...') for @Enumerated(EnumType.STRING) by
     * default, and ddl-auto=update never alters an existing column -- so adding
     * a value to the Java enum leaves the database rejecting it with "Data
     * truncated for column". This column was still enum('APPROVED','PENDING',
     * 'REJECTED') when REVERSING and REVERSED were added, and settlements.status
     * had been stuck on enum('PAID','PENDING') since long before that, silently
     * making COMPLETED and AWAITING_APPROVAL unwritable.
     *
     * An explicit varchar means the next enum value needs no DDL at all.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(32)")
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

    public void startReversal(){
        checkStatus(ExpenseStatus.APPROVED, "start reversal");
        this.status=ExpenseStatus.REVERSING;
    }

    public void completeReversal(){
        checkStatus(ExpenseStatus.REVERSING, "complete reversal");
        this.status=ExpenseStatus.REVERSED;
    }

    public void cancelReversal(){
        checkStatus(ExpenseStatus.REVERSING, "cancel reversal");
        this.status=ExpenseStatus.APPROVED;
    }

    private void checkStatus(ExpenseStatus required, String action){
        if(this.status != required){
            throw new InvalidExpenseStatusException(String.format(
                "Cannot %s expense %d: status is %s, expected %s",
                action, id, status, required));
        }
    }
}
