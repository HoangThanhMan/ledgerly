package dev.ledgerly.ledger;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account and its current balance.
 *
 * @param ownerId the user the account belongs to. Null for system accounts, and for wallets opened before accounts
 *     had owners.
 */
public record AccountView(
        UUID id, AccountType type, Money balance, @Nullable UUID ownerId, Instant createdAt) {}
