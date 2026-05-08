package com.be9expensphie.expense.repository;

import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.HouseholdRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HouseholdMemberSummaryRepository extends JpaRepository<HouseholdMemberSummary, Long> {
    Optional<HouseholdMemberSummary> findByUserIdAndHouseholdId(Long userId, Long householdId);
    Optional<HouseholdMemberSummary> findByHouseholdIdAndRole(Long householdId, HouseholdRole role);
    List<HouseholdMemberSummary> findByHouseholdId(Long householdId);
}
