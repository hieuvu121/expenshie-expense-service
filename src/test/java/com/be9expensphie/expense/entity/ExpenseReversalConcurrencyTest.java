package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ReversalState;
import com.be9expensphie.expense.repository.ExpenseReversalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The re-request sweep racing settlement-service's reply over one saga row.
 *
 * Both read the saga while it is AWAITING_SETTLEMENT. The reply completes it
 * and commits; the sweep then commits its re-request. Hibernate updates every
 * column, so without a version the sweep's stale state overwrites COMPLETED
 * and reopens a finished saga -- the sweep keeps re-requesting, settlement
 * replays ACCEPTED, and onDecided marks the saga FAILED for a reversal that
 * succeeded.
 *
 * Most likely right after expense-service restarts: the reply consumer
 * replays its backlog from earliest while the first sweep finds the same
 * sagas overdue.
 *
 * Two genuine transactions, for the same reason as ExpenseConcurrencyTest.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /* See OutboxWriterTest: without this the transactions below self-commit. */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class ExpenseReversalConcurrencyTest {

    private static final String SAGA = "saga-1";

    @Autowired private ExpenseReversalRepository reversalRepo;
    @Autowired private PlatformTransactionManager txManager;

    /* NOT_SUPPORTED on every test: see ExpenseConcurrencyTest. */

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    @BeforeEach
    void clear() {
        reversalRepo.deleteAll();
    }

    private void anAwaitingSaga() {
        Instant longAgo = Instant.now().minusSeconds(120);
        inTx(() -> reversalRepo.save(ExpenseReversal.builder()
                .sagaId(SAGA).expenseId(42L).householdId(3L)
                .state(ReversalState.AWAITING_SETTLEMENT)
                .requestedAt(longAgo).lastRequestedAt(longAgo)
                .attempts(1)
                .build()));
    }

    private ExpenseReversal load() {
        return inTx(() -> reversalRepo.findById(SAGA).orElseThrow());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aLateSweepCannotReopenASagaTheReplyCompleted() {
        anAwaitingSaga();

        ExpenseReversal seenBySweep = load();
        ExpenseReversal seenByReply = load();

        /* The reply lands first. */
        inTx(() -> {
            seenByReply.complete();
            return reversalRepo.save(seenByReply);
        });

        /* The sweep's copy still says AWAITING_SETTLEMENT, so its own check passes. */
        seenBySweep.recordReRequest(Instant.now());

        assertThatThrownBy(() -> inTx(() -> reversalRepo.save(seenBySweep)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        ExpenseReversal stored = load();
        assertThat(stored.getState()).isEqualTo(ReversalState.COMPLETED);
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    /* The mirror image: a reply arriving after the sweep wrote is retried, not lost. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aReplyThatLosesToTheSweepIsRejectedAndCanBeRetried() {
        anAwaitingSaga();

        ExpenseReversal seenBySweep = load();
        ExpenseReversal seenByReply = load();

        inTx(() -> {
            seenBySweep.recordReRequest(Instant.now());
            return reversalRepo.save(seenBySweep);
        });

        seenByReply.complete();
        assertThatThrownBy(() -> inTx(() -> reversalRepo.save(seenByReply)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        /* What the error handler's retry does: read again, apply again. */
        inTx(() -> {
            ExpenseReversal fresh = reversalRepo.findById(SAGA).orElseThrow();
            fresh.complete();
            return reversalRepo.save(fresh);
        });

        ExpenseReversal stored = load();
        assertThat(stored.getState()).isEqualTo(ReversalState.COMPLETED);
        assertThat(stored.getAttempts()).isEqualTo(2);
    }
}
