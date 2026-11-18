package com.be9expensphie.expense.config;

import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reversal reply listener's error handling, without a broker.
 *
 * c8302f9 defined the handler but never attached it, and nothing failed: an
 * unattached handler is silently replaced by Spring's default (ten immediate
 * attempts, no classification). The first test is the one that would have
 * caught that. The others pin what the handler does once it is attached.
 *
 * Exceptions are wrapped in ListenerExecutionFailedException because that is
 * how they reach the handler from a @KafkaListener; classification has to see
 * through the wrapper to the cause.
 */
class ReversalReplyErrorHandlerTest {

    private KafkaConsumerConfig config;
    private final ConsumerRecord<String, ExpenseReversalDecided> record =
            new ConsumerRecord<>("expense-reversal-replies", 0, 7L, "42", null);
    /*
     * Kafka's own test double and a real, never-started container: Mockito's
     * inline mock maker cannot instrument these on JDK 25.
     */
    private final MockConsumer<String, ExpenseReversalDecided> consumer =
            new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    private ConcurrentMessageListenerContainer<String, ExpenseReversalDecided> container;

    @BeforeEach
    void setUp() {
        config = new KafkaConsumerConfig();
        /* Only read when building the consumer factory; no connection is made. */
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        container = config.expenseReversalDecidedKafkaListenerContainerFactory()
                .createContainer("expense-reversal-replies");
    }

    @Test
    void theReplyListenerContainerUsesTheReversalErrorHandler() {
        CommonErrorHandler attached = container.getCommonErrorHandler();

        assertThat(attached).isInstanceOf(DefaultErrorHandler.class);
        /*
         * The default handler is also a DefaultErrorHandler, so the type alone
         * proves nothing. Ours is the one that refuses to retry a business
         * failure; the default would retry it.
         */
        assertThat(recoversImmediately((DefaultErrorHandler) attached,
                new InvalidExpenseStatusException("Cannot complete reversal expense 42"))).isTrue();
    }

    @Test
    void aBusinessFailureIsNotRetried() {
        DefaultErrorHandler handler = config.expenseReversalDecidedErrorHandler();

        assertThat(recoversImmediately(handler,
                new InvalidExpenseStatusException("Cannot complete reversal expense 42"))).isTrue();
    }

    /* handleOne returning false means "seek back and try again" -- a retry. */
    @Test
    void anOptimisticLockConflictIsRetried() {
        DefaultErrorHandler handler = config.expenseReversalDecidedErrorHandler();

        assertThat(recoversImmediately(handler,
                new OptimisticLockingFailureException("expense 42 changed"))).isFalse();
    }

    private boolean recoversImmediately(DefaultErrorHandler handler, RuntimeException cause) {
        return handler.handleOne(
                new ListenerExecutionFailedException("listener failed", cause),
                record, consumer, container);
    }
}
