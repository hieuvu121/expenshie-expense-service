package com.be9expensphie.expense.service;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.ExpenseReversalRequested;
import com.be9expensphie.common.event.ReversalOutcome;
import com.be9expensphie.expense.entity.ExpenseEntity;
import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.outbox.OutboxWriter;
import com.be9expensphie.expense.repository.ExpenseRepository;
import com.be9expensphie.expense.repository.ExpenseReversalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Drives the expense side of the reversal saga.
 *
 * The admin is told REVERSING, never "reversed", which is what makes the
 * compensation acceptable: when settlement-service refuses, nothing already
 * promised has to be retracted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpenseReversalService {

    static final String REQUESTS_TOPIC = "expense-reversal-requests";

    private final ExpenseRepository expenseRepo;
    private final ExpenseReversalRepository reversalRepo;
    private final ExpenseService expenseService;
    private final OutboxWriter outbox;

    /**
     * Moves an approved expense into REVERSING and asks settlement-service.
     *
     * One transaction: the status change and the outbox row commit together, so
     * an expense can never sit in REVERSING with no request on its way.
     */
    @Transactional
    public ExpenseReversal requestReversal(Long householdId, Long expenseId, Long userId) {
        expenseService.checkAdmin(householdId, userId);
        ExpenseEntity expense = expenseService.findExpense(householdId, expenseId);

        /*
         * Idempotent by design. A double-click or a retried request must return
         * the reversal already in flight rather than start a second one -- two
         * sagas racing over the same settlements means one gets refused for the
         * other's work.
         */
        var inFlight = reversalRepo.findByExpenseIdAndState(expenseId, ReversalState.AWAITING_SETTLEMENT);
        if (inFlight.isPresent()) {
            return inFlight.get();
        }

        if (expense.getStatus() != ExpenseStatus.APPROVED) {
            throw new RuntimeException("Only an approved expense can be reversed");
        }

        Instant now = Instant.now();
        ExpenseReversal reversal = reversalRepo.save(ExpenseReversal.builder()
                .sagaId(UUID.randomUUID().toString())
                .expenseId(expenseId)
                .householdId(householdId)
                .requestedByMemberId(expense.getCreatedByMemberId())
                .state(ReversalState.AWAITING_SETTLEMENT)
                .requestedAt(now)
                .lastRequestedAt(now)
                .attempts(1)
                .build());

        expense.setStatus(ExpenseStatus.REVERSING);
        expenseRepo.save(expense);

        publishRequest(reversal);
        log.info("Reversal {} requested for expenseId={}", reversal.getSagaId(), expenseId);
        return reversal;
    }

    /**
     * Applies settlement-service's answer.
     *
     * ACCEPTED is the point of no return: the debts are gone, so the expense
     * must follow. REFUSED is the only thing that compensates -- see
     * ReversalReRequestSweep for why silence must not.
     */
    @Transactional
    public void onDecided(ExpenseReversalDecided decision) {
        ExpenseReversal reversal = reversalRepo.findById(decision.getSagaId()).orElse(null);
        if (reversal == null) {
            log.warn("Decision for unknown saga {}", decision.getSagaId());
            return;
        }

        /* A redelivered or duplicated reply must not re-open a finished saga. */
        if (reversal.getState() != ReversalState.AWAITING_SETTLEMENT) {
            log.info("Ignoring decision for saga {} already in {}",
                    reversal.getSagaId(), reversal.getState());
            return;
        }

        ExpenseEntity expense = expenseRepo.findById(reversal.getExpenseId())
                .orElseThrow(() -> new IllegalStateException(
                        "Reversal " + reversal.getSagaId() + " references a missing expense"));

        if (decision.getOutcome() == ReversalOutcome.ACCEPTED) {
            expense.setStatus(ExpenseStatus.REVERSED);
            reversal.setState(ReversalState.COMPLETED);
            log.info("Reversal {} completed for expenseId={}", reversal.getSagaId(), expense.getId());
        } else {
            /* THE COMPENSATION. A true inverse: straight back to APPROVED. */
            expense.setStatus(ExpenseStatus.APPROVED);
            reversal.setState(ReversalState.CANCELLED);
            reversal.setFailureReason(decision.getReason());
            log.info("Reversal {} refused for expenseId={}: {}",
                    reversal.getSagaId(), expense.getId(), decision.getReason());
        }

        expenseRepo.save(expense);
        reversalRepo.save(reversal);
    }

    /**
     * Publishes the request for a saga, from scratch or again.
     *
     * Public because ReversalReRequestSweep lives outside this package. The
     * sagaId is always the caller's existing one -- a re-request that minted a
     * new id would be a second saga racing the first over the same settlements.
     */
    public void publishRequest(ExpenseReversal reversal) {
        outbox.write(REQUESTS_TOPIC, String.valueOf(reversal.getExpenseId()),
                ExpenseReversalRequested.builder()
                        .sagaId(reversal.getSagaId())
                        .householdId(reversal.getHouseholdId())
                        .expenseId(reversal.getExpenseId())
                        .requestedByMemberId(reversal.getRequestedByMemberId())
                        .build());
    }

    @Transactional(readOnly = true)
    public ExpenseReversal status(Long householdId, Long expenseId, Long userId) {
        expenseService.checkAdmin(householdId, userId);
        return reversalRepo.findFirstByExpenseIdOrderByRequestedAtDesc(expenseId)
                .orElseThrow(() -> new RuntimeException("No reversal for this expense"));
    }
}
