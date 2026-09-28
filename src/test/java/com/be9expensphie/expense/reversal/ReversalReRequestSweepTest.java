package com.be9expensphie.expense.reversal;

import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.repository.ExpenseReversalRepository;
import com.be9expensphie.expense.service.ExpenseReversalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The sweep must re-request and must never compensate.
 *
 * If settlement-service voided the debts and the reply was lost, rolling the
 * expense back to APPROVED would leave it approved with VOIDED settlements --
 * the exact divergence this saga exists to prevent, caused by the saga.
 * expense-service cannot tell "refused" from "accepted but the reply vanished",
 * and unlike a payment provider there is nothing to query, so the only safe
 * protocol is retry-until-answered.
 *
 * ExpenseReversalService is subclassed rather than mocked -- it is a concrete
 * class and the inline mock maker cannot instrument those on JDK 25.
 */
@ExtendWith(MockitoExtension.class)
class ReversalReRequestSweepTest {

    @Mock private ExpenseReversalRepository reversalRepo;

    private RecordingReversalService reversalService;
    private ReversalReRequestSweep sweep;

    private static class RecordingReversalService extends ExpenseReversalService {
        final List<String> republished = new ArrayList<>();

        RecordingReversalService() {
            super(null, null, null, null);
        }

        @Override
        public void publishRequest(ExpenseReversal reversal) {
            republished.add(reversal.getSagaId());
        }
    }

    @BeforeEach
    void setUp() {
        reversalService = new RecordingReversalService();
        sweep = new ReversalReRequestSweep(reversalRepo, reversalService);
        ReflectionTestUtils.setField(sweep, "timeoutSeconds", 60L);
        ReflectionTestUtils.setField(sweep, "maxAttempts", 10);
    }

    private static ExpenseReversal stale(String sagaId, int attempts) {
        return ExpenseReversal.builder()
                .sagaId(sagaId)
                .expenseId(42L)
                .householdId(3L)
                .state(ReversalState.AWAITING_SETTLEMENT)
                .requestedAt(Instant.now().minusSeconds(300))
                .lastRequestedAt(Instant.now().minusSeconds(120))
                .attempts(attempts)
                .build();
    }

    private void staleReversals(ExpenseReversal... rows) {
        when(reversalRepo.findByStateAndLastRequestedAtBefore(
                org.mockito.ArgumentMatchers.eq(ReversalState.AWAITING_SETTLEMENT), any(Instant.class)))
                .thenReturn(List.of(rows));
    }

    /*
     * The whole point. An unanswered reversal is re-asked with the SAME sagaId,
     * which ReversalDecisionService recognises on its own voided rows and
     * answers ACCEPTED again.
     */
    @Test
    void anUnansweredReversalIsReRequestedWithTheSameSagaId() {
        ExpenseReversal reversal = stale("saga-1", 1);
        staleReversals(reversal);

        sweep.resweep();

        assertThat(reversalService.republished).containsExactly("saga-1");
        assertThat(reversal.getAttempts()).isEqualTo(2);
    }

    /** The assertion that matters most: silence never rolls anything back. */
    @Test
    void theSweepNeverCompensates() {
        ExpenseReversal reversal = stale("saga-1", 1);
        staleReversals(reversal);

        sweep.resweep();

        assertThat(reversal.getState()).isEqualTo(ReversalState.AWAITING_SETTLEMENT);
        assertThat(reversal.getFailureReason()).isNull();
    }

    /*
     * Past the attempt ceiling it stops publishing but leaves the saga open and
     * logs at ERROR. REVERSING is visible and recoverable; a state that
     * contradicts settlement_db is not.
     */
    @Test
    void anExhaustedReversalStopsBeingPublishedButStaysOpen() {
        ExpenseReversal reversal = stale("saga-1", 10);
        staleReversals(reversal);

        sweep.resweep();

        assertThat(reversalService.republished).isEmpty();
        assertThat(reversal.getState()).isEqualTo(ReversalState.AWAITING_SETTLEMENT);
        assertThat(reversal.getAttempts()).isEqualTo(10);
    }

    @Test
    void nothingStaleIsANoOp() {
        staleReversals();

        sweep.resweep();

        assertThat(reversalService.republished).isEmpty();
    }
}
