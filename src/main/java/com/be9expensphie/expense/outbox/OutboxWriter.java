package com.be9expensphie.expense.outbox;

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

    public void write(String topic, String aggregateId, Object event) {
        try {
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .topic(topic)
                    .eventId(UUID.randomUUID().toString())
                    .payload(objectMapper.writeValueAsString(event))
                    .createdAt(Instant.now())
                    .build());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize outbox payload for " + topic, e);
        }
    }
}
