package com.be9expensphie.expense.config;

import com.be9expensphie.common.event.AiResponseEvent;
import com.be9expensphie.common.event.ExpenseReversalDecided;
import com.be9expensphie.common.event.HouseholdMemberEvent;
import com.be9expensphie.expense.exception.InvalidExpenseStatusException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    //2 error: optimisticLock should continue retry but InvalidExpenseStatus should stop
    public DefaultErrorHandler expenseReversalDecidedErrorHandler(){
        var backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(200);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);

        var handler = new DefaultErrorHandler(
                (record, ex) -> log.error("Giving up on reversal reply key={} offset={}",
                        record.key(), record.offset(), ex),
                backOff);
        handler.addNotRetryableExceptions(InvalidExpenseStatusException.class);
        return handler;
    }

    @Bean
    public ConsumerFactory<String, HouseholdMemberEvent> householdMemberEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "expense-service-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, HouseholdMemberEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConsumerFactory<String, AiResponseEvent> aiResponseConsumerFactory(){
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "expense-ai-reply-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, AiResponseEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    //concurrent means many threads
    public ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent> householdMemberEventKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(householdMemberEventConsumerFactory());
        return factory;
    }

    @Bean
    public KafkaMessageListenerContainer<String,AiResponseEvent> aiReplyListenerContainer(){
        ContainerProperties containerProps = new ContainerProperties("ai-response-events");
        return new KafkaMessageListenerContainer<>(aiResponseConsumerFactory(), containerProps);
    }

    /* The reversal saga's inbound reply, on its own group. */
    @Bean
    public ConsumerFactory<String, ExpenseReversalDecided> expenseReversalDecidedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "expense-reversal-group");
        /*
         * earliest, not the client default of latest. A saga message published
         * while this consumer was down would otherwise be skipped outright --
         * which is survivable only because the re-request sweep exists, and
         * there is no reason to lean on it for an ordinary restart.
         */
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ExpenseReversalDecided.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ExpenseReversalDecided>
            expenseReversalDecidedKafkaListenerContainerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, ExpenseReversalDecided>();
        factory.setConsumerFactory(expenseReversalDecidedConsumerFactory());
        factory.setCommonErrorHandler(expenseReversalDecidedErrorHandler());
        return factory;
    }
}
