package com.be9expensphie.expense.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /*
     * No row locking: expense-service runs single-instance in both compose
     * files, so exactly one poller drains this table and strict id order is
     * preserved. Scaling past one replica needs either leader election on
     * OutboxPublisher.drain() or a claim query using FOR UPDATE SKIP LOCKED —
     * see the ordering note on that class before doing either.
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
