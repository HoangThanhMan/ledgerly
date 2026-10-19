package dev.ledgerly.idempotency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ledgerly.idempotency.internal.persistence.IdempotencyKeyRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The one branch of claiming a key that a real database cannot be made to take on demand. */
class IdempotencyTransactionsTest {

    private final IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    private final IdempotencyTransactions transactions = new IdempotencyTransactions(
            keys,
            new StaticListableBeanFactory().getBeanProvider(FaultInjector.class),
            Duration.ofSeconds(30),
            Duration.ofHours(24));

    @Test
    void keyDeletedByTheCleanupBetweenInsertAndReadIsNotClaimed() {
        when(keys.insert(eq("key"), eq("hash"), any(), any(), any())).thenReturn(false);
        when(keys.find("key")).thenReturn(Optional.empty());

        // The caller is told to retry: its next attempt finds no row and claims the key with a plain insert.
        assertThat(transactions.claim("key", "hash")).isEqualTo(new Claim.HeldByAnother());

        verify(keys, never()).reclaim(any(), any(), any());
    }
}
