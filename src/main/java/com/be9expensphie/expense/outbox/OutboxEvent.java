package com.be9expensphie.expense.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * An event that has been decided but not yet published.
 *
 * Written in the same transaction as the state change it describes, which is
 * the whole point: the row and the change commit together or not at all. The
 * previous code sent to Kafka from inside the transaction, so a rollback after
 * the send left settlement-service acting on an approval that never happened.
 *
 * The payload is serialized on the way in, while the entities are still
 * managed. Anything that defers serialization until after the commit has to
 * contend with ExpenseEntity.splitDetails being lazy and the entity detached.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "published_at,id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Becomes the Kafka message key, so per-aggregate ordering survives. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 64)
    private String topic;

    /**
     * Identifies this event for consumers that need to discard duplicates.
     *
     * OutboxPublisher is at-least-once by design — it republishes anything it
     * could not confirm — so without this a redelivery is indistinguishable
     * from a new event.
     */
    @Column(name = "event_id", nullable = false, length = 36, unique = true)
    private String eventId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the broker has acknowledged it. */
    @Column(name = "published_at")
    private Instant publishedAt;
}
