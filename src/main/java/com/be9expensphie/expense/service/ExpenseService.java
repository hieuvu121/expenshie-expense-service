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
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final HouseholdMembershipCache membershipCache;

    private static final String AI_SUGGESTION = "ai_suggestion";
    private static final String EXPENSE_IN_RANGE = "expense_in_range";

    @Transactional
    public CreateExpenseResponseDTO createExpense(Long householdId, CreateExpenseRequestDTO createRequest, Long userId) {
        HouseholdMemberSummary member = householdMemberSummaryRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(userId, householdId)
                .orElseThrow(() -> new RuntimeException("User is not in this household"));

        HouseholdMemberSummary admin = householdMemberSummaryRepo.findByHouseholdIdAndRoleAndRemovedAtIsNull(householdId, HouseholdRole.ROLE_ADMIN)
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

    // The page is projected straight into the DTO by the repository, so no
    // ExpenseEntity is loaded and the creator name comes back in the same
    // statement — see ExpenseRepository.findPage.
    //
    // The transaction stays: it keeps the membership check and the page on one
    // pooled connection. Without it each would acquire its own.
    @Transactional(readOnly = true)
    public CursorDTO<CreateExpenseResponseDTO> getExpense(Long householdId, ExpenseStatus status, int limit, Long cursor, Long userId) {
        membershipCache.requireMember(userId, householdId);

        // No Sort here: the @Query carries its own ORDER BY, and passing one in
        // the Pageable too makes Spring Data append a duplicate sort clause.
        Pageable pageable = PageRequest.of(0, limit + 1);
        long from = cursor != null ? cursor : Long.MAX_VALUE;

        List<CreateExpenseResponseDTO> rows = (status == null)
                ? expenseRepo.findPage(householdId, from, pageable)
                : expenseRepo.findPageByStatus(householdId, status, from, pageable);

        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }

        Long nextCursor = rows.isEmpty() ? null : rows.get(rows.size() - 1).getId();

        return CursorDTO.<CreateExpenseResponseDTO>builder()
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .data(rows)
                .build();
    }

    // Without a transaction the two statements below each opened and committed
    // their own, so a single-expense read cost two transactions.
    @Transactional(readOnly = true)
    public CreateExpenseResponseDTO getSingleExpense(Long householdId, Long expenseId, Long userId) {
        membershipCache.requireMember(userId, householdId);

        return expenseRepo.findDtoByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new RuntimeException("Expense not found"));
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
    @Transactional(readOnly = true)
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

        return toDTOs(expenseRepo.findExpenseInRange(householdId, status, start, end));
    }

    @Transactional(readOnly = true)
    public List<CreateExpenseResponseDTO> getExpenseLastMonth(Long householdId) {
        return toDTOs(expenseRepo.findExpenseInLastMonth(householdId));
    }

    /**
     * Maps a list with every creator name resolved in one query.
     *
     * The per-row toDTO() overload issues findById() for each expense. On the
     * paged endpoint that was a 10-row N+1; here the queries are unpaginated,
     * so a household with a month of expenses paid one round trip per row —
     * and, before these methods had a transaction, one transaction per row too.
     */
    // MUST return a mutable ArrayList, not Stream.toList() or List.of().
    //
    // getExpenseByPeriod is @Cacheable into Redis, and RedisConfig calls
    // activateDefaultTyping(..., NON_FINAL, ...) — Jackson writes a type id
    // only for non-final types. ArrayList is non-final and serializes as
    // ["java.util.ArrayList", [...]]; the immutable classes behind
    // Stream.toList() and List.of() are final, so they serialize as a bare
    // [...] with no type id. The write succeeds either way, but the read
    // fails: GenericJackson2JsonRedisSerializer deserializes to Object and
    // demands the type id, giving "Unexpected token (START_OBJECT), expected
    // VALUE_STRING". First call after an eviction works, every cache hit 400s.
    //
    // Verified by A/B: switching this to Stream.toList() reproduces it.
    private List<CreateExpenseResponseDTO> toDTOs(List<ExpenseEntity> expenses) {
        if (expenses.isEmpty()) return new ArrayList<>();
        Set<Long> creatorIds = expenses.stream()
                .map(ExpenseEntity::getCreatedByMemberId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = creatorIds.isEmpty()
                ? Map.of()
                : householdMemberSummaryRepo.findAllById(creatorIds).stream()
                        .collect(Collectors.toMap(HouseholdMemberSummary::getMemberId,
                                                  HouseholdMemberSummary::getFullName));
        return expenses.stream().map(e -> toDTO(e, names))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public ExpenseEntity findExpense(Long householdId, Long expenseId) {
        return expenseRepo.findByIdAndHouseholdId(expenseId, householdId)
                .orElseThrow(() -> new RuntimeException("No expense found"));
    }

    /** Single-entity path, for callers that already hold exactly one expense. */
    private CreateExpenseResponseDTO toDTO(ExpenseEntity expense) {
        String createdBy = householdMemberSummaryRepo.findById(expense.getCreatedByMemberId())
                .map(HouseholdMemberSummary::getFullName)
                .orElse("Unknown");
        return build(expense, createdBy);
    }

    /** Page path — names are pre-resolved in one query by creatorNamesFor(). */
    private CreateExpenseResponseDTO toDTO(ExpenseEntity expense, Map<Long, String> creatorNames) {
        return build(expense, creatorNames.getOrDefault(expense.getCreatedByMemberId(), "Unknown"));
    }

    private CreateExpenseResponseDTO build(ExpenseEntity expense, String createdBy) {
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
        HouseholdMemberSummary member = householdMemberSummaryRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(userId, householdId)
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
