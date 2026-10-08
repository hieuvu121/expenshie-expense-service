package com.be9expensphie.expense.service;

import com.be9expensphie.common.event.DomainEvent;
import com.be9expensphie.expense.dto.ExpenseDTO.CreateExpenseRequestDTO;
import com.be9expensphie.expense.dto.SplitDTO.SplitRequestDTO;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.common.enums.HouseholdRole;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.outbox.OutboxWriter;
import com.be9expensphie.expense.producer.ExpenseEventProducer;
import com.be9expensphie.expense.repository.ExpenseRepository;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.be9expensphie.expense.validation.ExpenseValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * An expense that is born APPROVED must announce itself as approved.
 *
 * createExpense marks an admin's own expense APPROVED on the spot, but
 * published only EXPENSE_CREATED -- and settlement-service reacts solely to
 * EXPENSE_APPROVED. So "APPROVED in expense_db" and "approved as far as
 * settlement-service is concerned" were different predicates, and an admin's
 * own expense produced no debt at all. Every e2e scenario in verify/ has to
 * create expenses as a member to work around it.
 *
 * ExpenseValidation and OutboxWriter are subclassed rather than mocked: both
 * are concrete classes, which the inline mock maker cannot instrument on the
 * JDK 25 these tests run under.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpenseCreationEventsTest {

    private static final long HOUSEHOLD_ID = 3L;
    private static final long ADMIN_USER = 7L;
    private static final long ADMIN_MEMBER = 11L;
    private static final long PLAIN_USER = 8L;
    private static final long PLAIN_MEMBER = 12L;

    @Mock private ExpenseRepository expenseRepo;
    @Mock private HouseholdMemberSummaryRepository membershipRepo;
    @Mock private CacheManager cacheManager;

    private RecordingOutbox outbox;
    private ExpenseService service;

    private record Written(String topic, String key, DomainEvent payload) {}

    private static class RecordingOutbox extends OutboxWriter {
        final List<Written> written = new ArrayList<>();

        RecordingOutbox() { super(null, null); }

        @Override
        public void write(String topic, String aggregateId, DomainEvent event) {
            written.add(new Written(topic, aggregateId, event));
        }
    }

    /** validateExpense talks to the repository; nothing here is testing it. */
    private static class PassThroughValidation extends ExpenseValidation {
        PassThroughValidation() { super(null); }

        @Override
        public void validateExpense(CreateExpenseRequestDTO request, Long householdId) { }
    }

    private static HouseholdMemberSummary member(long memberId, long userId, HouseholdRole role) {
        return HouseholdMemberSummary.builder()
                .memberId(memberId).householdId(HOUSEHOLD_ID).userId(userId)
                .fullName("Member " + memberId).role(role)
                .build();
    }

    @BeforeEach
    void setUp() {
        outbox = new RecordingOutbox();
        service = new ExpenseService(expenseRepo, membershipRepo, null,
                new PassThroughValidation(), cacheManager, new ExpenseEventProducer(),
                outbox, null);

        when(membershipRepo.findByHouseholdIdAndRoleAndRemovedAtIsNull(HOUSEHOLD_ID, HouseholdRole.ROLE_ADMIN))
                .thenReturn(Optional.of(member(ADMIN_MEMBER, ADMIN_USER, HouseholdRole.ROLE_ADMIN)));
        when(membershipRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(ADMIN_USER, HOUSEHOLD_ID))
                .thenReturn(Optional.of(member(ADMIN_MEMBER, ADMIN_USER, HouseholdRole.ROLE_ADMIN)));
        when(membershipRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(PLAIN_USER, HOUSEHOLD_ID))
                .thenReturn(Optional.of(member(PLAIN_MEMBER, PLAIN_USER, HouseholdRole.ROLE_MEMBER)));
        when(membershipRepo.findAllById(any())).thenReturn(List.of());

        // The entity is returned as-is, with an id, so recordEvents sees it.
        when(expenseRepo.save(any(ExpenseEntity.class))).thenAnswer(inv -> {
            ExpenseEntity e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });
    }

    private CreateExpenseRequestDTO request(long payerMemberId) {
        return CreateExpenseRequestDTO.builder()
                .amount(new BigDecimal("100"))
                .category("Groceries")
                .currency("VND")
                .method(Method.AMOUNT)
                .date(LocalDate.of(2026, 10, 8))
                .splits(List.of(SplitRequestDTO.builder()
                        .memberId(payerMemberId).amount(new BigDecimal("100")).build()))
                .build();
    }

    private List<String> expenseEventTypes() {
        return outbox.written.stream()
                .filter(w -> w.topic().equals("expense-events"))
                .map(w -> w.payload().toString())
                .map(s -> s.contains("EXPENSE_APPROVED") ? "EXPENSE_APPROVED"
                        : s.contains("EXPENSE_CREATED") ? "EXPENSE_CREATED" : "OTHER")
                .toList();
    }

    /*
     * The bug. Settlements exist only because settlement-service consumed an
     * EXPENSE_APPROVED, so an expense that skips straight to APPROVED has to
     * emit one or the debt is never created and nothing ever notices.
     */
    @Test
    void anAdminsOwnExpenseIsBornApprovedAndSaysSo() {
        service.createExpense(HOUSEHOLD_ID, request(ADMIN_MEMBER), ADMIN_USER);

        assertThat(expenseEventTypes()).contains("EXPENSE_APPROVED");
    }

    /** A member's expense is PENDING, so approval comes later and must not fire now. */
    @Test
    void aMembersExpenseAnnouncesOnlyCreation() {
        service.createExpense(HOUSEHOLD_ID, request(PLAIN_MEMBER), PLAIN_USER);

        assertThat(expenseEventTypes()).containsExactly("EXPENSE_CREATED");
        assertThat(expenseEventTypes()).doesNotContain("EXPENSE_APPROVED");
    }

    /*
     * The websocket push is built for APPROVED and REJECTED only
     * (ExpenseEventProducer.build), so a born-approved expense should reach
     * connected clients the same way an approval does.
     */
    @Test
    void aBornApprovedExpenseAlsoReachesConnectedClients() {
        service.createExpense(HOUSEHOLD_ID, request(ADMIN_MEMBER), ADMIN_USER);

        assertThat(outbox.written).anySatisfy(w ->
                assertThat(w.topic()).isEqualTo("websocket-events"));
    }

    @Test
    void aMembersPendingExpensePushesNothingToClients() {
        service.createExpense(HOUSEHOLD_ID, request(PLAIN_MEMBER), PLAIN_USER);

        assertThat(outbox.written).noneSatisfy(w ->
                assertThat(w.topic()).isEqualTo("websocket-events"));
    }

    @Test
    void theStatusOnTheSavedRowMatchesWhatIsAnnounced() {
        service.createExpense(HOUSEHOLD_ID, request(ADMIN_MEMBER), ADMIN_USER);

        assertThat(outbox.written).anySatisfy(w ->
                assertThat(w.payload().toString()).contains(ExpenseStatus.APPROVED.name()));
    }
}
