package com.be9expensphie.expense.service;

import com.be9expensphie.expense.entity.HouseholdMemberSummary;
import com.be9expensphie.expense.repository.HouseholdMemberSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdMembershipCacheTest {

    private static final long USER_ID = 7L;
    private static final long HOUSEHOLD_ID = 3L;

    @Mock
    private HouseholdMemberSummaryRepository repo;

    @InjectMocks
    private HouseholdMembershipCache cache;

    @BeforeEach
    void setUp() {
        withTtl(30);
    }

    private void withTtl(long ttlSeconds) {
        ReflectionTestUtils.setField(cache, "ttlSeconds", ttlSeconds);
        ReflectionTestUtils.setField(cache, "maxSize", 1000L);
        cache.initCache();
    }

    private void databaseSays(boolean member) {
        when(repo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(anyLong(), anyLong()))
                .thenReturn(member ? Optional.of(new HouseholdMemberSummary()) : Optional.empty());
    }

    @Test
    void repeatedChecksHitTheDatabaseOnce() {
        databaseSays(true);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();

        verify(repo, times(1)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    /** The revocation path: without the invalidate, the positive would stand for the full TTL. */
    @Test
    void invalidateForcesTheNextCheckBackToTheDatabase() {
        when(repo.findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID))
                .thenReturn(Optional.of(new HouseholdMemberSummary()))
                .thenReturn(Optional.empty());

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();

        cache.invalidate(USER_ID, HOUSEHOLD_ID);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isFalse();
        verify(repo, times(2)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    @Test
    void negativeAnswersAreCachedToo() {
        databaseSays(false);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isFalse();
        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isFalse();

        verify(repo, times(1)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    @Test
    void entriesAreKeyedPerUserAndHousehold() {
        databaseSays(true);

        cache.isMember(USER_ID, HOUSEHOLD_ID);
        cache.invalidate(USER_ID, 999L);

        assertThat(cache.isMember(USER_ID, HOUSEHOLD_ID)).isTrue();
        verify(repo, times(1)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    @Test
    void zeroTtlBypassesTheCacheEntirely() {
        withTtl(0);
        databaseSays(true);

        cache.isMember(USER_ID, HOUSEHOLD_ID);
        cache.isMember(USER_ID, HOUSEHOLD_ID);

        verify(repo, times(2)).findByUserIdAndHouseholdIdAndRemovedAtIsNull(USER_ID, HOUSEHOLD_ID);
    }

    @Test
    void requireMemberThrowsForANonMember() {
        databaseSays(false);

        assertThatThrownBy(() -> cache.requireMember(USER_ID, HOUSEHOLD_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("User not in household");
    }

    @Test
    void invalidateBeforeAnyLookupIsHarmless() {
        cache.invalidate(USER_ID, HOUSEHOLD_ID);

        verify(repo, never()).findByUserIdAndHouseholdIdAndRemovedAtIsNull(anyLong(), anyLong());
    }
}
