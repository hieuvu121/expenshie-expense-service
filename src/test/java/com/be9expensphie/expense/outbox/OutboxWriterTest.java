package com.be9expensphie.expense.outbox;

import com.be9expensphie.common.event.ExpenseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour that makes the outbox worth having: the event row lives or dies
 * with the transaction that produced it.
 *
 * Runs against a real transaction on H2 rather than mocks, because a mocked
 * repository cannot roll anything back and would pass no matter what the
 * writer does.
 */
@DataJpaTest
@Import({OutboxWriter.class, OutboxWriterTest.TestConfig.class})
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        /*
         * MUST be overridden. application.properties sets this true because
         * Hikari is configured with auto-commit=false in production, and it
         * tells Hibernate not to bother disabling autocommit itself. The
         * datasource @DataJpaTest substitutes has autocommit ON, so inheriting
         * the production value leaves every statement self-committing and no
         * rollback in this file would ever roll anything back -- the tests pass
         * or fail for reasons unrelated to the code under test.
         */
        "spring.jpa.properties.hibernate.connection.provider_disables_autocommit=false"
})
class OutboxWriterTest {

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxRepository repository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    /*
     * These tests commit for real (NOT_SUPPORTED opts out of @DataJpaTest's
     * automatic rollback, which would otherwise hide the very thing under
     * test), so rows survive into the next test unless cleared here.
     */
    @BeforeEach
    void clearOutbox() {
        repository.deleteAll();
    }

    private static ExpenseEvent event() {
        return ExpenseEvent.builder()
                .expenseId(42L)
                .householdId(3L)
                .status("APPROVED")
                .amount(new BigDecimal("12.50"))
                .currency("AUD")
                .date(LocalDate.of(2026, 9, 18))
                .eventType("EXPENSE_APPROVED")
                .splits(List.of())
                .build();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aCommittedTransactionLeavesTheEventBehind() {
        new TransactionTemplate(txManager).executeWithoutResult(
                status -> writer.write("expense-events", "3", event()));

        List<OutboxEvent> rows = repository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo("expense-events");
        assertThat(row.getAggregateId()).isEqualTo("3");
        assertThat(row.getEventId()).isNotBlank();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getPayload()).contains("\"expenseId\":42", "EXPENSE_APPROVED");
    }

    /*
     * The failure the outbox exists to prevent. Before this change the Kafka
     * send had already left the process by the time anything could roll back,
     * so settlement-service saw an approval the database never kept.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRolledBackTransactionLeavesNoEvent() {
        assertThatThrownBy(() ->
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    writer.write("expense-events", "3", event());
                    throw new IllegalStateException("cache eviction blew up after the write");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eachEventGetsItsOwnId() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            writer.write("expense-events", "3", event());
            writer.write("websocket-events", "3", event());
        });

        assertThat(repository.findAll())
                .extracting(OutboxEvent::getEventId)
                .doesNotHaveDuplicates()
                .hasSize(2);
    }
}
