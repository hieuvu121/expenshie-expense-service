package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.HouseholdRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "household_member_summary",
    uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "household_id"}),
    indexes = {
        @Index(name = "idx_hms_user_household", columnList = "user_id,household_id"),
        @Index(name = "idx_hms_household", columnList = "household_id")
    }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HouseholdMemberSummary {

    @Id
    @Column(name = "member_id")
    @EqualsAndHashCode.Include
    private Long memberId;

    @Column(name = "household_id", nullable = false)
    private Long householdId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HouseholdRole role;

    /**
     * Set when MEMBER_LEFT arrives; null means an active member.
     *
     * Membership is revoked by stamping this rather than deleting the row,
     * because the row is also what resolves the creator's name on expenses
     * they already created — ExpenseRepository's left joins and
     * ExpenseService.toDTO() both read fullName from here. Deleting would
     * turn every one of their past expenses into "Unknown".
     *
     * Consequence: every authorization lookup must filter on
     * removedAtIsNull, while the name-resolution lookups must not.
     */
    @Column(name = "removed_at")
    private Instant removedAt;
}
