package com.be9expensphie.expense.outbox;

import com.be9expensphie.common.event.ExpenseEvent;
import com.be9expensphie.common.event.WebSocketEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains outbox_event to Kafka.
 *
 * Rows are deserialized back into their event type and sent through the same
 * typed templates the producers used to call directly. That keeps the wire
 * format byte-identical — type headers included — so no consumer's
 * deserializer configuration has to change.
 *
 * AT-LEAST-ONCE: publishedAt is stamped only after the broker acknowledges, so
 * a crash between the send and the stamp republishes on restart. That is the
 * right way round — the alternative drops events — and it is why every row
 * carries an eventId for consumers to discard duplicates by.
 *
 * ORDERING: one publisher drains strictly by id, which preserves per-aggregate
 * order, and a refused send stops the batch rather than stepping over it. Both
 * assume a single instance; see OutboxRepository before scaling this service
 * past one replica.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, ExpenseEvent> expenseEventKafkaTemplate;
    private final KafkaTemplate<String, WebSocketEvent> webSocketKafkaTemplate;

    @Value("${app.outbox.retention-days:7}")
    private int retentionDays;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public void drain() {
        List<OutboxEvent> batch = outboxRepository.findTop100ByPublishedAtIsNullOrderByIdAsc();
        if (batch.isEmpty()) {
            return;
        }

        List<OutboxEvent> published = new ArrayList<>(batch.size());
        for (OutboxEvent row : batch) {
            try {
                send(row);
                row.setPublishedAt(Instant.now());
                published.add(row);
            } catch (Exception e) {
                /*
                 * Stop, do not continue. Publishing row n+1 after row n failed
                 * would reorder two events for the same expense, and a PENDING
                 * arriving after its APPROVED leaves settlement-service acting
                 * on the wrong final state. The row keeps publishedAt null, so
                 * the next tick starts again from here.
                 */
                log.warn("Outbox publish failed at id={}, topic={}; retrying next tick",
                        row.getId(), row.getTopic(), e);
                break;
            }
        }

        if (!published.isEmpty()) {
            /*
             * Explicit rather than relying on dirty checking: it says plainly
             * that only acknowledged rows are marked, and it keeps the boundary
             * visible to anyone reading the loop above.
             */
            outboxRepository.saveAll(published);
        }
    }

    /*
     * get() on purpose. An unresolved send would let the loop stamp publishedAt
     * for a record the broker never accepted, which is precisely the lost-event
     * case this class exists to prevent.
     */
    private void send(OutboxEvent row) throws Exception {
        switch (row.getTopic()) {
            case "expense-events" -> expenseEventKafkaTemplate
                    .send(row.getTopic(), row.getAggregateId(),
                            objectMapper.readValue(row.getPayload(), ExpenseEvent.class))
                    .get();
            case "websocket-events" -> webSocketKafkaTemplate
                    .send(row.getTopic(), row.getAggregateId(),
                            objectMapper.readValue(row.getPayload(), WebSocketEvent.class))
                    .get();
            default -> throw new IllegalStateException("No template for topic " + row.getTopic());
        }
    }

    /** Published rows are history nobody reads; without this the table grows forever. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void purgePublished() {
        int deleted = outboxRepository.deletePublishedBefore(
                Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("Purged {} published outbox rows", deleted);
        }
    }
}
