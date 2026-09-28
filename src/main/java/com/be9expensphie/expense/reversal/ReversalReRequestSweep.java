package com.be9expensphie.expense.reversal;

import com.be9expensphie.expense.entity.ExpenseReversal;
import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.repository.ExpenseReversalRepository;
import com.be9expensphie.expense.service.ExpenseReversalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-asks settlement-service about reversals that never got an answer.
 *
 * IT MUST NEVER COMPENSATE. If settlement-service voided the debts and the
 * reply was lost, rolling the expense back to APPROVED would leave it approved
 * with VOIDED settlements -- exactly the divergence this saga exists to
 * prevent, caused by the saga. expense-service cannot tell "refused" from
 * "accepted but the reply vanished", and unlike a payment provider there is
 * nothing to query, so the only safe protocol is retry-until-answered.
 *
 * Re-publishing carries the SAME sagaId, which ReversalDecisionService
 * recognises on its own voided rows and answers ACCEPTED again.
 *
 * Only ExpenseReversalDecided(REFUSED) moves REVERSING back to APPROVED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReversalReRequestSweep {

    private final ExpenseReversalRepository reversalRepo;
    private final ExpenseReversalService reversalService;

    @Value("${app.reversal.request-timeout-seconds:60}")
    private long timeoutSeconds;

    @Value("${app.reversal.max-attempts:10}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.reversal.sweep-interval-ms:15000}")
    @Transactional
    public void resweep() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(timeoutSeconds));
        List<ExpenseReversal> stale =
                reversalRepo.findByStateAndLastRequestedAtBefore(ReversalState.AWAITING_SETTLEMENT, cutoff);

        for (ExpenseReversal reversal : stale) {
            if (reversal.getAttempts() >= maxAttempts) {
                /*
                 * Stop publishing, keep the saga open, make noise. The expense
                 * stays in REVERSING, which is visible and recoverable, rather
                 * than being rolled into a state that may contradict
                 * settlement_db.
                 */
                log.error("Reversal {} for expenseId={} unanswered after {} attempts -- needs attention",
                        reversal.getSagaId(), reversal.getExpenseId(), reversal.getAttempts());
                continue;
            }
            reversal.setAttempts(reversal.getAttempts() + 1);
            reversal.setLastRequestedAt(Instant.now());
            reversalRepo.save(reversal);
            reversalService.publishRequest(reversal);
            log.warn("Re-requesting reversal {} (attempt {})", reversal.getSagaId(), reversal.getAttempts());
        }
    }
}
