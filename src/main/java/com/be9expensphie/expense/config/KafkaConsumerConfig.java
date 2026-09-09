package com.be9expensphie.expense.config;

import com.be9expensphie.common.event.AiResponseEvent;
import com.be9expensphie.common.event.HouseholdMemberEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Configuration
public class KafkaConsumerConfig {
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

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

    /**
     * Second view of household-member-events, on a group id unique to this JVM.
     *
     * The factory above shares expense-service-group, so one replica consumes
     * each event — correct for the database write, wrong for cache
     * invalidation, which every replica has to perform against its own
     * Caffeine map. A unique group makes each instance a broadcast receiver.
     *
     * auto.offset.reset=latest is load-bearing: the group is new on every
     * start, and 'earliest' would replay the whole topic to invalidate a cache
     * that is empty at startup anyway. These throwaway groups hold no
     * committed offsets and Kafka expires them after offsets.retention.minutes
     * (7 days by default).
     */
    @Bean
    public ConsumerFactory<String, HouseholdMemberEvent> householdMemberCacheInvalidationConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "expense-cache-invalidation-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.be9expensphie.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, HouseholdMemberEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent>
            householdMemberCacheInvalidationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, HouseholdMemberEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(householdMemberCacheInvalidationConsumerFactory());
        return factory;
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
}
