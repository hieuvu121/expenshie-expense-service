package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.HouseholdRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdMemberSummaryRepository extends JpaRepository<HouseholdMemberSummary, Long> {

    // Authorization lookups: these decide what a caller may do, so they must
    // not see a member retired by MEMBER_LEFT. The RemovedAtIsNull suffix is
    // the whole revocation mechanism — dropping it from any of these reopens
    // access to a removed member.
    //
    // idx_hms_user_household still drives the first one; removed_at is a
    // residual filter over the one or two rows that match.
    Optional<HouseholdMemberSummary> findByUserIdAndHouseholdIdAndRemovedAtIsNull(Long userId, Long householdId);
    Optional<HouseholdMemberSummary> findByHouseholdIdAndRoleAndRemovedAtIsNull(Long householdId, HouseholdRole role);
    List<HouseholdMemberSummary> findByHouseholdIdAndRemovedAtIsNull(Long householdId);

    // The name-resolution reads are deliberately NOT declared here: they go
    // through the inherited findById()/findAllById() and through
    // ExpenseRepository's left joins, all of which stay unfiltered so a
    // removed member's past expenses keep their creator name.
}
