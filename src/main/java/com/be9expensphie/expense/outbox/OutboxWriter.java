package com.be9expensphie.expense.outbox;

import com.be9expensphie.common.event.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Records an outgoing event inside the caller's transaction.
 *
 * Always called from a @Transactional service method, never on its own — the
 * guarantee comes entirely from sharing that transaction.
 *
 * Deliberately has no exception handling. If the event cannot be recorded, the
 * state change that produced it has to roll back too; swallowing here would
 * quietly restore the inconsistency the outbox exists to remove.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Takes DomainEvent rather than Object deliberately: it is a compile-time
     * guarantee that nothing leaves through the outbox without an id. Given the
     * publisher is at-least-once, an unidentifiable event is one a consumer
     * cannot tell apart from its own redelivery.
     */
    public void write(String topic, String aggregateId, DomainEvent event) {
        try {
            /*
             * One id, used twice: stamped on the payload so the consumer sees
             * it, and kept on the row so a republish sends the same value.
             * Generating it per publish attempt would defeat the point.
             */
            String eventId = UUID.randomUUID().toString();
            event.setEventId(eventId);

            outboxRepository.save(OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .topic(topic)
                    .eventId(eventId)
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload for " + topic, e);
        }
    }
}
