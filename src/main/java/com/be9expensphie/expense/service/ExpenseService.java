package com.be9expensphie.expense.service;

import com.be9expensphie.expense.dto.CursorDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseResponseDTO;
import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.ExpenseSplitDetailsEntity;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.HouseholdRole;
import com.be9expensphie.expense.enums.TimeRange;
import com.be9expensphie.expense.producer.ExpenseEventProducer;
import com.be9expensphie.expense.repository.ExpenseRepository;
import com.be9expensphie.expense.repository.ExpenseSplitDetailsRepository;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.be9expensphie.expense.validation.ExpenseValidation;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepo;
    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;
    private final ExpenseSplitDetailsRepository expenseSplitDetailsRepo;
    private final ExpenseValidation expenseValidation;
    private final CacheManager cacheManager;
    private final ExpenseEventProducer expenseEventProducer;

    private static final String AI_SUGGESTION = "ai_suggestion";
    private static final String EXPENSE_IN_RANGE = "expense_in_range";

    @Transactional
    public CreateExpenseResponseDTO createExpense(Long householdId, CreateExpenseRequestDTO createRequest, Long userId) {
        HouseholdMemberSummary member = householdMemberSummaryRepo.findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User is not in this household"));

        HouseholdMemberSummary admin = householdMemberSummaryRepo.findByHouseholdIdAndRole(householdId, HouseholdRole.ROLE_ADMIN)
                .orElseThrow(() -> new RuntimeException("No admin found for household"));

        expenseValidation.validateExpense(createRequest, householdId);

        ExpenseStatus status = (member.getRole() == HouseholdRole.ROLE_ADMIN) ? ExpenseStatus.APPROVED : ExpenseStatus.PENDING;

        ExpenseEntity expense = ExpenseEntity.builder()
                .amount(createRequest.getAmount())
                .category(createRequest.getCategory())
                .description(createRequest.getDescription())
                .createdByMemberId(member.getMemberId())
                .reviewedByMemberId(admin.getMemberId())
                .method(createRequest.getMethod())
                .status(status)
                .householdId(householdId)
                .date(createRequest.getDate())
                .currency(createRequest.getCurrency())
                .build();

        List<Long> memberIds = createRequest.getSplits().stream().map(SplitRequestDTO::getMemberId).toList();
        Map<Long, HouseholdMemberSummary> memberMap = householdMemberSummaryRepo.findAllById(memberIds)
                .stream().collect(Collectors.toMap(HouseholdMemberSummary::getMemberId, m -> m));

        for (SplitRequestDTO split : createRequest.getSplits()) {
            expense.getSplitDetails().add(ExpenseSplitDetailsEntity.builder()
                    .amount(split.getAmount())
                    .memberId(split.getMemberId())
                    .expense(expense)
                    .build());
        }

        ExpenseEntity savedExpense = expenseRepo.save(expense);
        expenseEventProducer.publish(savedExpense, "EXPENSE_CREATED");

        evictExpenseInRangeCaches(householdId, status);
        evictCacheForAiSuggestion(householdId);
        return toDTO(savedExpense);
    }

    public CursorDTO<CreateExpenseResponseDTO> getExpense(Long householdId, ExpenseStatus status, int limit, Long cursor, Long userId) {
        householdMemberSummaryRepo.findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User not in household"));

        Pageable pageable = PageRequest.of(0, limit + 1, Sort.by("id").descending());
        List<ExpenseEntity> expenses;

        if (status == null) {
            expenses = expenseRepo.findNextExpense(cursor != null ? cursor : Long.MAX_VALUE, householdId, pageable);
            if (!expenses.isEmpty()) {
                expenseRepo.fetchSplitDetails(expenses);
            }
        } else {
            expenses = expenseRepo.findExpenseByStatus(householdId, status, cursor != null ? cursor : Long.MAX_VALUE, pageable);
        }

        boolean hasMore = expenses.size() > limit;
        if (hasMore) {
            expenses = expenses.subList(0, limit);
        }

        Long nextCursor = expenses.isEmpty() ? null : expenses.get(expenses.size() - 1).getId();

        return CursorDTO.<CreateExpenseResponseDTO>builder()
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .data(expenses.stream().map(this::toDTO).toList())
                .build();
    }

    public CreateExpenseResponseDTO getSingleExpense(Long householdId, Long expenseId, Long userId) {
        householdMemberSummaryRepo.findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User not in this group"));

        ExpenseEntity expense = expenseRepo.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new RuntimeException("Expense not found"));

        return toDTO(expense);
    }

    @Transactional
    public CreateExpenseResponseDTO updateExpense(Long householdId, Long expenseId, CreateExpenseRequestDTO request, Long userId) {
        checkAdmin(householdId, userId);

        ExpenseEntity expense = expenseRepo.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new RuntimeException("Expense not found"));

        if (request.getAmount() != null) expense.setAmount(request.getAmount());
        if (request.getMethod() != null) expense.setMethod(request.getMethod());
        if (request.getDate() != null) expense.setDate(request.getDate());
        if (request.getCurrency() != null) expense.setCurrency(request.getCurrency());
        if (request.getDescription() != null) expense.setDescription(request.getDescription());
        if (request.getCategory() != null) expense.setCategory(request.getCategory());

        if (request.getSplits() != null && !request.getSplits().isEmpty()) {
            List<ExpenseSplitDetailsEntity> existingSplits = expenseSplitDetailsRepo.findByExpenseWithMember(expense);
            Map<Long, ExpenseSplitDetailsEntity> splitMap = existingSplits.stream()
                    .collect(Collectors.toMap(ExpenseSplitDetailsEntity::getMemberId, s -> s));

            for (SplitRequestDTO splitRequest : request.getSplits()) {
                ExpenseSplitDetailsEntity split = splitMap.get(splitRequest.getMemberId());
                if (split == null) {
                    split = ExpenseSplitDetailsEntity.builder()
                            .expense(expense)
                            .memberId(splitRequest.getMemberId())
                            .amount(splitRequest.getAmount())
                            .build();
                    expense.getSplitDetails().add(split);
                } else {
                    split.setAmount(splitRequest.getAmount());
                }
            }
        }

        ExpenseEntity savedExpense = expenseRepo.save(expense);
        evictCacheForAiSuggestion(householdId);
        return toDTO(savedExpense);
    }

    @Transactional
    public CreateExpenseResponseDTO acceptExpense(Long householdId, Long expenseId, Long userId) {
        checkAdmin(householdId, userId);

        ExpenseEntity expense = findExpense(householdId, expenseId);
        if (expense.getStatus() != ExpenseStatus.PENDING) {
            throw new RuntimeException("Only pending expense can be approved");
        }

        expense.setStatus(ExpenseStatus.APPROVED);
        expenseRepo.save(expense);

        expenseEventProducer.publish(expense, "EXPENSE_APPROVED");

        evictCacheForAiSuggestion(householdId);
        evictExpenseInRangeCaches(householdId, ExpenseStatus.PENDING);
        evictExpenseInRangeCaches(householdId, ExpenseStatus.APPROVED);

        return toDTO(expense);
    }

    @Transactional
    public void rejectExpense(Long householdId, Long expenseId, Long userId) {
        checkAdmin(householdId, userId);

        ExpenseEntity expense = findExpense(householdId, expenseId);
        if (expense.getStatus() != ExpenseStatus.PENDING) {
            throw new RuntimeException("Only pending expense can be rejected");
        }

        expense.setStatus(ExpenseStatus.REJECTED);
        expenseRepo.save(expense);

        expenseEventProducer.publish(expense, "EXPENSE_REJECTED");

        evictExpenseInRangeCaches(householdId, ExpenseStatus.PENDING);
        evictExpenseInRangeCaches(householdId, ExpenseStatus.REJECTED);
        evictCacheForAiSuggestion(householdId);
    }

    @Cacheable(key = "#householdId + ':' + #status + ':' + #range", cacheNames = EXPENSE_IN_RANGE)
    public List<CreateExpenseResponseDTO> getExpenseByPeriod(ExpenseStatus status, Long householdId, TimeRange range) {
        LocalDate now = LocalDate.now();
        LocalDate start;
        LocalDate end;

        switch (range) {
            case DAILY:
                start = now.with(DayOfWeek.MONDAY);
                end = start.plusWeeks(1);
                break;
            case WEEKLY:
                start = now.minusWeeks(8).with(DayOfWeek.MONDAY);
                end = now.plusDays(1);
                break;
            case MONTHLY:
                start = now.withDayOfMonth(1);
                end = start.plusMonths(1);
                break;
            default:
                throw new RuntimeException("Invalid range of time");
        }

        return expenseRepo.findExpenseInRange(householdId, status, start, end)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    public List<CreateExpenseResponseDTO> getExpenseLastMonth(Long householdId) {
        return expenseRepo.findExpenseInLastMonth(householdId)
                .stream().map(this::toDTO).toList();
    }

    public ExpenseEntity findExpense(Long householdId, Long expenseId) {
        return expenseRepo.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new RuntimeException("No expense found"));
    }

    private CreateExpenseResponseDTO toDTO(ExpenseEntity expense) {
        String createdBy = householdMemberSummaryRepo.findById(expense.getCreatedByMemberId())
                .map(HouseholdMemberSummary::getFullName)
                .orElse("Unknown");

        return CreateExpenseResponseDTO.builder()
                .id(expense.getId())
                .amount(expense.getAmount())
                .category(expense.getCategory())
                .description(expense.getDescription())
                .status(expense.getStatus())
                .date(expense.getDate())
                .method(expense.getMethod())
                .currency(expense.getCurrency())
                .createdBy(createdBy)
                .build();
    }

    private void checkAdmin(Long householdId, Long userId) {
        HouseholdMemberSummary member = householdMemberSummaryRepo.findByUserIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new RuntimeException("Not a member of this household"));
        if (member.getRole() != HouseholdRole.ROLE_ADMIN) {
            throw new RuntimeException("Only admin can perform this action");
        }
    }

    private void evictCacheForAiSuggestion(Long householdId) {
        Cache cache = cacheManager.getCache(AI_SUGGESTION);
        if (cache != null) cache.evict(String.valueOf(householdId));
    }

    private void evictExpenseInRangeCaches(Long householdId, ExpenseStatus changedStatus) {
        Cache cache = cacheManager.getCache(EXPENSE_IN_RANGE);
        if (cache == null) return;
        for (TimeRange range : TimeRange.values()) {
            cache.evict(householdId + ":" + changedStatus + ":" + range);
            cache.evict(householdId + ":" + null + ":" + range);
        }
    }
}
