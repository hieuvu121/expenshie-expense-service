package com.be9expensphie.expense.service;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ReversalOutcome;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.common.enums.HouseholdRole;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.outbox.OutboxWriter;
import com.be9expensphie.expense.repository.ExpenseRepository;
import com.be9expensphie.expense.repository.ExpenseReversalRepository;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.be9expensphie.common.event.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The expense side of the saga.
 *
 * The admin is told REVERSING, never "reversed", which is what makes the
 * compensation acceptable: when settlement-service refuses, nothing already
 * promised has to be retracted.
 *
 * OutboxWriter is subclassed rather than mocked -- concrete classes cannot be
 * instrumented by the inline mock maker on JDK 25.
 */
@ExtendWith(MockitoExtension.class)
class ExpenseReversalServiceTest {

    private static final long HOUSEHOLD_ID = 3L;
    private static final long EXPENSE_ID = 42L;
    private static final long USER_ID = 7L;
    private static final String SAGA = "saga-1";

    @Mock private ExpenseRepository expenseRepo;
    @Mock private ExpenseReversalRepository reversalRepo;
    @Mock private HouseholdMemberSummaryRepository membershipRepo;

    private RecordingOutbox outbox;
    private ExpenseReversalService service;

    private record Written(String topic, String key, DomainEvent payload) {}

    private static class RecordingOutbox extends OutboxWriter {
        final List<Written> written = new ArrayList<>();

        RecordingOutbox() {
            super(null, null);
        }

        @Override
        public void write(String topic, String aggregateId, DomainEvent event) {
            written.add(new Written(topic, aggregateId, event));
        }
    }

    @BeforeEach
    void setUp() {
        outbox = new RecordingOutbox();
        ExpenseService expenseService = new ExpenseService(
                expenseRepo, membershipRepo, null, null, null, null, null, null);
        service = new ExpenseReversalService(expenseRepo, reversalRepo, expenseService, outbox);
    }

    private void callerIsAdmin() {
        when(membershipRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(Optional.of(HouseholdMemberSummary.builder()
                        .memberId(11L).householdId(HOUSEHOLD_ID).userId(USER_ID)
                        .fullName("Dana").role(HouseholdRole.ROLE_ADMIN)
                        .build()));
    }

    private ExpenseEntity expense(ExpenseStatus status) {
        ExpenseEntity e = ExpenseEntity.builder()
                .id(EXPENSE_ID).householdId(HOUSEHOLD_ID)
                .amount(new BigDecimal("100.00")).currency("AUD")
                .method(Method.EQUAL).date(LocalDate.of(2026, 9, 1))
                .status(status).createdByMemberId(11L)
                .build();
        when(expenseRepo.findByIdAndHouseholdId(EXPENSE_ID, HOUSEHOLD_ID)).thenReturn(Optional.of(e));
        return e;
    }

    private static ExpenseReversal reversal(ReversalState state) {
        return ExpenseReversal.builder()
                .sagaId(SAGA).expenseId(EXPENSE_ID).householdId(HOUSEHOLD_ID)
                .state(state).requestedAt(Instant.now()).lastRequestedAt(Instant.now())
                .attempts(1)
                .build();
    }

    @Test
    void requestingMovesTheExpenseToReversingAndAsksSettlementService() {
        callerIsAdmin();
        ExpenseEntity e = expense(ExpenseStatus.APPROVED);
        when(reversalRepo.findByExpenseIdAndState(EXPENSE_ID, ReversalState.AWAITING_SETTLEMENT))
                .thenReturn(Optional.empty());
        when(reversalRepo.save(any(ExpenseReversal.class))).thenAnswer(i -> i.getArgument(0));

        ExpenseReversal saga = service.requestReversal(HOUSEHOLD_ID, EXPENSE_ID, USER_ID);

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.REVERSING);
        assertThat(saga.getState()).isEqualTo(ReversalState.AWAITING_SETTLEMENT);
        assertThat(outbox.written).singleElement().satisfies(w -> {
            assertThat(w.topic()).isEqualTo("expense-reversal-requests");
            assertThat(w.key()).isEqualTo("42");
        });
    }

    @Test
    void onlyAnApprovedExpenseCanBeReversed() {
        callerIsAdmin();
        expense(ExpenseStatus.PENDING);
        when(reversalRepo.findByExpenseIdAndState(EXPENSE_ID, ReversalState.AWAITING_SETTLEMENT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestReversal(HOUSEHOLD_ID, EXPENSE_ID, USER_ID))
                .hasMessageContaining("Only an approved expense");

        assertThat(outbox.written).isEmpty();
    }

    /*
     * Two sagas racing over the same settlements means one gets refused for the
     * other's work, so a repeat request returns the one in flight.
     */
    @Test
    void aSecondRequestReturnsTheReversalAlreadyInFlight() {
        callerIsAdmin();
        expense(ExpenseStatus.REVERSING);
        ExpenseReversal existing = reversal(ReversalState.AWAITING_SETTLEMENT);
        when(reversalRepo.findByExpenseIdAndState(EXPENSE_ID, ReversalState.AWAITING_SETTLEMENT))
                .thenReturn(Optional.of(existing));

        ExpenseReversal saga = service.requestReversal(HOUSEHOLD_ID, EXPENSE_ID, USER_ID);

        assertThat(saga).isSameAs(existing);
        assertThat(outbox.written).isEmpty();
        verify(reversalRepo, never()).save(any());
    }

    @Test
    void acceptedCompletesTheReversal() {
        ExpenseEntity e = ExpenseEntity.builder()
                .id(EXPENSE_ID).status(ExpenseStatus.REVERSING).build();
        ExpenseReversal saga = reversal(ReversalState.AWAITING_SETTLEMENT);
        when(reversalRepo.findById(SAGA)).thenReturn(Optional.of(saga));
        when(expenseRepo.findById(EXPENSE_ID)).thenReturn(Optional.of(e));

        service.onDecided(ExpenseReversalDecided.builder()
                .sagaId(SAGA).expenseId(EXPENSE_ID)
                .outcome(ReversalOutcome.ACCEPTED).settlementIds(List.of(7L))
                .build());

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.REVERSED);
        assertThat(saga.getState()).isEqualTo(ReversalState.COMPLETED);
    }

    /** The compensation: a true inverse, straight back to APPROVED. */
    @Test
    void refusedPutsTheExpenseBackAndKeepsTheReason() {
        ExpenseEntity e = ExpenseEntity.builder()
                .id(EXPENSE_ID).status(ExpenseStatus.REVERSING).build();
        ExpenseReversal saga = reversal(ReversalState.AWAITING_SETTLEMENT);
        when(reversalRepo.findById(SAGA)).thenReturn(Optional.of(saga));
        when(expenseRepo.findById(EXPENSE_ID)).thenReturn(Optional.of(e));

        service.onDecided(ExpenseReversalDecided.builder()
                .sagaId(SAGA).expenseId(EXPENSE_ID)
                .outcome(ReversalOutcome.REFUSED)
                .reason("Cannot reverse: this expense has already been settled")
                .settlementIds(List.of(9L))
                .build());

        assertThat(e.getStatus()).isEqualTo(ExpenseStatus.APPROVED);
        assertThat(saga.getState()).isEqualTo(ReversalState.CANCELLED);
        assertThat(saga.getFailureReason()).contains("already been settled");
    }

    /*
     * The sweep re-requests, so a duplicate reply is routine rather than
     * exceptional. It must not re-open a saga that already finished.
     */
    @Test
    void aDuplicateReplyForAFinishedSagaChangesNothing() {
        ExpenseReversal saga = reversal(ReversalState.COMPLETED);
        when(reversalRepo.findById(SAGA)).thenReturn(Optional.of(saga));

        service.onDecided(ExpenseReversalDecided.builder()
                .sagaId(SAGA).expenseId(EXPENSE_ID)
                .outcome(ReversalOutcome.REFUSED).reason("late refusal")
                .build());

        assertThat(saga.getState()).isEqualTo(ReversalState.COMPLETED);
        assertThat(saga.getFailureReason()).isNull();
        verify(expenseRepo, never()).save(any());
    }

    @Test
    void aReplyForAnUnknownSagaIsIgnored() {
        when(reversalRepo.findById(SAGA)).thenReturn(Optional.empty());

        service.onDecided(ExpenseReversalDecided.builder()
                .sagaId(SAGA).outcome(ReversalOutcome.ACCEPTED).build());

        verify(expenseRepo, never()).save(any());
    }
}
