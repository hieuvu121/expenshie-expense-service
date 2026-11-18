package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
/*
 * No setters and no builder: every change goes through the aggregate's own
 * methods, so its invariants cannot be bypassed. The no-arg constructor is for
 * Hibernate only.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    public void approve(){
        checkStatus(ExpenseStatus.PENDING, "approve");
        this.status=ExpenseStatus.APPROVED;
    }

    public void reject(){
        checkStatus(ExpenseStatus.PENDING, "reject");
        this.status=ExpenseStatus.REJECTED;
    }

    private void checkStatus(ExpenseStatus required, String action){
        if(this.status != required){
            throw new InvalidExpenseStatusException(String.format(
                "Cannot %s expense %d: status is %s, expected %s",
                action, id, status, required));
        }
    }

    public record Split(Long memberId, BigDecimal amount){}

    public static ExpenseEntity createExpense(Long householdId, BigDecimal amount, String currency,
                                              LocalDate date, String category, String description,
                                              Method method, List<Split> splits,
                                              Long createdByMemberId, Long adminMemberId,
                                              boolean createdByAdmin){
        /* Validate first, so a half-built expense never exists. */
        requireValid(amount, currency, category, method, date, splits);

        return createObject(householdId, amount, currency, date, category, description,
                method, splits, createdByMemberId, adminMemberId, createdByAdmin);
    }

    /*
     * Editing an approved expense silently diverged from the settlements
     * derived from it at approval time. Amount and splits changed here,
     * settlement_db kept the old figures, and nothing reconciled them --
     * permanently, since no event was published. Publishing one would not
     * have helped either: createSettlementsForExpense guards on
     * existsByExpenseIdAndFromMemberId and would have skipped the correction,
     * so the dedup guard and the correction were in direct conflict and the
     * guard won.
     *
     * An approved expense is corrected by reversing it and posting a new one.
     * See ExpenseReversalService.
     *
     * A null argument keeps the current value. The result is validated as a
     * whole, with the same rules as creation: changing only the amount must
     * still leave the splits summing to it.
     */
    public void updateExpense(BigDecimal amount, String currency, LocalDate date, String category,
                              String description, Method method, List<Split> splits){
        checkStatus(ExpenseStatus.PENDING, "edit");

        BigDecimal newAmount = amount != null ? amount : this.amount;
        String newCurrency = currency != null ? currency : this.currency;
        LocalDate newDate = date != null ? date : this.date;
        String newCategory = category != null ? category : this.category;
        Method newMethod = method != null ? method : this.method;
        List<Split> newSplits = splits != null ? splits : currentSplits();

        requireValid(newAmount, newCurrency, newCategory, newMethod, newDate, newSplits);

        this.amount = newAmount;
        this.currency = newCurrency;
        this.date = newDate;
        this.category = newCategory;
        this.method = newMethod;
        if (description != null) {
            this.description = description;
        }
        replaceSplits(newSplits);
    }

    private static ExpenseEntity createObject(Long householdId, BigDecimal amount, String currency,
                                              LocalDate date, String category, String description,
                                              Method method, List<Split> splits,
                                              Long createdByMemberId, Long adminMemberId,
                                              boolean createdByAdmin){
        ExpenseEntity e = new ExpenseEntity();
        e.householdId = householdId;
        e.amount = amount;
        e.currency = currency;
        e.date = date;
        e.category = category;
        e.description = description;
        e.method = method;
        e.createdByMemberId = createdByMemberId;
        e.reviewedByMemberId = adminMemberId;
        /* An admin's own expense needs no review, so it is born approved. */
        e.status = createdByAdmin ? ExpenseStatus.APPROVED : ExpenseStatus.PENDING;
        e.replaceSplits(splits);
        return e;
    }

    private List<Split> currentSplits(){
        return splitDetails.stream()
                .map(d -> new Split(d.getMemberId(), d.getAmount()))
                .toList();
    }

    /*
     * Makes the splits exactly the given list, matching by member.
     *
     * Updated in place rather than cleared and re-added: Hibernate flushes
     * inserts before deletes, so re-adding a member's split would hit the
     * unique (expense_id, member_id) before the old row is gone. A member
     * left out of the list loses their split -- orphanRemoval deletes it --
     * so no stale share can survive an edit and break the sum.
     */
    private void replaceSplits(List<Split> splits){
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        for (Split s : splits) {
            wanted.put(s.memberId(), s.amount());
        }

        splitDetails.removeIf(d -> !wanted.containsKey(d.getMemberId()));
        for (ExpenseSplitDetailsEntity d : splitDetails) {
            d.setAmount(wanted.remove(d.getMemberId()));
        }
        wanted.forEach((memberId, amount) -> splitDetails.add(ExpenseSplitDetailsEntity.builder()
                .memberId(memberId)
                .amount(amount)
                .expense(this)
                .build()));
    }

    private static void requireValid(BigDecimal amount, String currency, String category,
                                     Method method, LocalDate date, List<Split> splits){
        requirePositive(amount);
        requireText(currency, "Currency");
        requireText(category, "Category");
        if (method == null) {
            throw new IllegalArgumentException("Method must be filled");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date must be filled");
        }
        requireValidSplits(splits, amount, method);
    }

    private static void requirePositive(BigDecimal amount){
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than 0, got " + amount);
        }
    }

    private static void requireText(String value, String field){
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be filled");
        }
    }

    private static void requireValidSplits(List<Split> splits, BigDecimal amount, Method method){
        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("Must have at least 1 split");
        }

        Set<Long> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Split s : splits) {
            if (s == null || s.memberId() == null) {
                throw new IllegalArgumentException("Every split needs a member");
            }
            if (s.amount() == null || s.amount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "Split amount for member " + s.memberId() + " must be >= 0");
            }
            /* The table has a unique (expense_id, member_id); catch it here, not as a raw constraint error. */
            if (!seen.add(s.memberId())) {
                throw new IllegalArgumentException("Member " + s.memberId() + " appears in more than one split");
            }
            sum = sum.add(s.amount());
        }

        /* compareTo, not equals: 10.0 and 10.00 are the same money but not equal(). */
        if (sum.compareTo(amount) != 0) {
            throw new IllegalArgumentException(String.format(
                    "Sum of splits is %s but amount is %s", sum, amount));
        }

        if (method == Method.EQUAL) {
            BigDecimal first = splits.get(0).amount();
            for (Split s : splits) {
                if (s.amount().compareTo(first) != 0) {
                    throw new IllegalArgumentException("Splits of each member should be equal for EQUAL");
                }
            }
        }
    }
}
