package com.be9expensphie.expense.entity;

import com.be9expensphie.expense.enums.ExpenseStatus;
import com.be9expensphie.expense.enums.Method;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import com.be9expensphie.expense.repository.ExpenseRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two admins reversing the same expense at once.
 *
 * startReversal() checks the status on the copy each transaction loaded, so
 * two requests that both read APPROVED both pass the check -- the aggregate
 * method alone cannot see the other transaction. What stops the second one is
 * @Version: its UPDATE matches no row once the first has committed, and the
 * whole transaction, saga row and outbox row included, rolls back.
 *
 * Runs two genuine transactions rather than detaching a copy, because the
 * failure being reproduced is about commit ordering.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /* See OutboxWriterTest: without this the transactions below self-commit. */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class ExpenseConcurrencyTest {

    @Autowired private ExpenseRepository expenseRepo;
    @Autowired private PlatformTransactionManager txManager;

    /*
     * NOT_SUPPORTED on every test. @DataJpaTest wraps each method in its own
     * transaction, and a TransactionTemplate inside that simply joins it -- one
     * persistence context, so the two reads below would hand back the same
     * managed instance and no conflict could ever occur.
     */

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    @BeforeEach
    void clear() {
        /* These commit for real, so rows outlive the test without this. */
        expenseRepo.deleteAll();
    }

    private Long anApprovedExpense() {
        return inTx(() -> expenseRepo.save(ExpenseFixtures.expense(null, ExpenseStatus.APPROVED)).getId());
    }

    private ExpenseStatus statusOf(Long id) {
        return inTx(() -> expenseRepo.findById(id).orElseThrow().getStatus());
    }

    /*
     * The one that matters. Both admins read APPROVED, so both pass
     * startReversal(); only the first commit may win.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void theSecondOfTwoConcurrentReversalsIsRejected() {
        Long id = anApprovedExpense();

        ExpenseEntity seenByAdminA = inTx(() -> expenseRepo.findById(id).orElseThrow());
        ExpenseEntity seenByAdminB = inTx(() -> expenseRepo.findById(id).orElseThrow());

        inTx(() -> {
            seenByAdminA.startReversal();
            return expenseRepo.save(seenByAdminA);
        });

        /* B's copy is stale but still says APPROVED, so the aggregate lets it through. */
        seenByAdminB.startReversal();

        assertThatThrownBy(() -> inTx(() -> expenseRepo.save(seenByAdminB)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(statusOf(id)).isEqualTo(ExpenseStatus.REVERSING);
    }

    /*
     * The same request arriving after the first committed: a fresh read sees
     * REVERSING, and this time the aggregate refuses before any write.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aReversalAfterTheFirstCommittedIsRefusedByTheAggregate() {
        Long id = anApprovedExpense();

        inTx(() -> {
            ExpenseEntity e = expenseRepo.findById(id).orElseThrow();
            e.startReversal();
            return expenseRepo.save(e);
        });

        assertThatThrownBy(() -> inTx(() -> {
            ExpenseEntity e = expenseRepo.findById(id).orElseThrow();
            e.startReversal();
            return expenseRepo.save(e);
        })).isInstanceOf(InvalidExpenseStatusException.class);

        assertThat(statusOf(id)).isEqualTo(ExpenseStatus.REVERSING);
    }

    /** Uncontended writes must stay ordinary -- the version is not a lock. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sequentialTransitionsAreUnaffected() {
        Long id = anApprovedExpense();

        inTx(() -> {
            ExpenseEntity e = expenseRepo.findById(id).orElseThrow();
            e.startReversal();
            return expenseRepo.save(e);
        });
        inTx(() -> {
            ExpenseEntity e = expenseRepo.findById(id).orElseThrow();
            e.completeReversal();
            return expenseRepo.save(e);
        });

        assertThat(statusOf(id)).isEqualTo(ExpenseStatus.REVERSED);
    }
}
