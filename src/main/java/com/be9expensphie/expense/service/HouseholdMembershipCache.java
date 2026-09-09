package com.be9expensphie.expense.service;

import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caches "is this user a member of this household" for the read paths.
 *
 * Every expense read runs this check, so it is one of only two statements a
 * list request issues. The lookup itself is cheap — roughly 256us against a
 * small table on an already-open pooled connection — which is exactly why the
 * earlier attempt to cache it in Redis was reverted: a Redis round trip
 * measures ~280us, so it swapped an equal-cost operation for a second serial
 * tail dependency and moved p95 from 4.69ms to 12.46ms.
 *
 * Caffeine has no network hop, so that trade does not apply. This is the same
 * shape as the gateway's JWT blacklist cache, which took Redis from ~1178
 * ops/sec to ~1.
 *
 * STALENESS: entries are invalidated explicitly by HouseholdMemberEventConsumer
 * — negatives on MEMBER_JOINED so a new member is not locked out, positives on
 * MEMBER_LEFT so a removed one loses access. The underlying lookup filters on
 * removedAtIsNull, so the database stops answering "member" the moment the
 * MEMBER_LEFT row is stamped.
 *
 * The residual window is per-replica. household-member-events is consumed by
 * expense-service-group, so exactly one replica clears its map from that
 * listener; HouseholdMemberCacheInvalidationConsumer exists to fan the same
 * event out to every replica on a unique group id. If that broadcast listener
 * is unhealthy, other replicas keep serving a stale positive until the TTL
 * expires — bounded by app.membership-cache-ttl-seconds, which set to 0
 * disables the cache and always hits the database.
 */
@Component
@RequiredArgsConstructor
public class HouseholdMembershipCache {

    private final HouseholdMemberSummaryRepository householdMemberSummaryRepo;

    @Value("${app.membership-cache-ttl-seconds:30}")
    private long ttlSeconds;

    @Value("${app.membership-cache-max-size:50000}")
    private long maxSize;

    private Cache<String, Boolean> cache;

    @PostConstruct
    void initCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
                .maximumSize(maxSize)
                .build();
    }

    private static String key(Long userId, Long householdId) {
        return userId + ":" + householdId;
    }

    public boolean isMember(Long userId, Long householdId) {
        if (ttlSeconds <= 0) {
            return lookup(userId, householdId);
        }
        return Boolean.TRUE.equals(
                cache.get(key(userId, householdId), k -> lookup(userId, householdId)));
    }

    /** Read paths only care that membership exists, not which member it is. */
    public void requireMember(Long userId, Long householdId) {
        if (!isMember(userId, householdId)) {
            throw new RuntimeException("User not in household");
        }
    }

    public void invalidate(Long userId, Long householdId) {
        if (cache != null) {
            cache.invalidate(key(userId, householdId));
        }
    }

    private boolean lookup(Long userId, Long householdId) {
        return householdMemberSummaryRepo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(userId, householdId).isPresent();
    }
}
