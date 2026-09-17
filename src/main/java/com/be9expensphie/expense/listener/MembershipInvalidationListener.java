package com.be9expensphie.expense.listener;

import com.be9expensphie.expense.service.HouseholdMembershipCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Clears this replica's cached membership answer when any replica commits a
 * membership change.
 *
 * Replaces HouseholdMemberCacheInvalidationConsumer, which did the same job on
 * a Kafka group whose id was a fresh UUID per JVM. Two things improve. The
 * throwaway groups are gone -- each one lingered in the broker for
 * offsets.retention.minutes after every restart and deploy. And the ordering is
 * now correct: that listener ran on a group independent of the one owning the
 * database write, so it could invalidate before removedAt was stamped and a
 * read landing in the gap would re-cache the stale positive. The publish now
 * happens in HouseholdMemberEventConsumer after the save, so by the time this
 * runs the row already says what it should.
 *
 * Redis pub/sub is at-most-once, so this is an optimisation and not the
 * correctness mechanism: anything missed is still bounded by
 * app.membership-cache-ttl-seconds, which is what the old broadcast fell back
 * to as well.
 *
 * Must stay write-free. Every replica runs this for the same message, so any
 * persistence here would execute N times over.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipInvalidationListener implements MessageListener {

    private final HouseholdMembershipCache membershipCache;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);

        /*
         * Nothing may escape this method: the container dispatches every
         * subscriber on a shared thread, so a thrown exception costs deliveries
         * that have nothing to do with this message.
         */
        int sep = body.indexOf(':');
        if (sep < 1 || sep == body.length() - 1) {
            log.warn("Ignoring malformed membership invalidation: {}", body);
            return;
        }
        try {
            membershipCache.invalidate(
                    Long.parseLong(body.substring(0, sep)),
                    Long.parseLong(body.substring(sep + 1)));
        } catch (NumberFormatException e) {
            log.warn("Ignoring membership invalidation with non-numeric ids: {}", body);
        }
    }
}
