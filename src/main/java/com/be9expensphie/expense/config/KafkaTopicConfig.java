package com.be9expensphie.expense.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the reversal topics explicitly.
 *
 * Everything else in this service relies on Kafka auto-creation, which gives a
 * single partition. These are keyed on expenseId so one expense's messages stay
 * ordered; with one partition that holds anyway, but the intent belongs in the
 * code rather than in a broker default.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic expenseReversalRequestsTopic() {
        return TopicBuilder.name("expense-reversal-requests").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic expenseReversalRepliesTopic() {
        return TopicBuilder.name("expense-reversal-replies").partitions(3).replicas(1).build();
    }
}
