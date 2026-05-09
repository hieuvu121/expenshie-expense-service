package com.be9expensphie.expense.config;

import com.be9expensphie.common.event.AiRequestEvent;
import com.be9expensphie.common.event.AiResponseEvent;
import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.common.event.WebSocketEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return props;
    }

    @Bean
    public KafkaTemplate<String, ExpenseEvent> expenseEventKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(baseProducerProps()));
    }

    @Bean
    public KafkaTemplate<String, WebSocketEvent> webSocketKafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(baseProducerProps()));
    }

    @Bean
    //template include both produce+consumer to push+wait for response
    public ReplyingKafkaTemplate<String, AiRequestEvent, AiResponseEvent> replyingKafkaTemplate(
            KafkaMessageListenerContainer<String, AiResponseEvent> aiReplyListenerContainer) {

        ReplyingKafkaTemplate<String, AiRequestEvent, AiResponseEvent> template =
                new ReplyingKafkaTemplate<>(
                        new DefaultKafkaProducerFactory<>(baseProducerProps()),
                        aiReplyListenerContainer
                );
        template.setDefaultReplyTimeout(Duration.ofSeconds(10));
        return template;
    }
}
