package dev.ledgerly.ledger;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** A written entry. {@code amount} is signed from the account's side, {@code balanceAfter} follows from it. */
public record EntryView(
        long id, UUID transactionId, UUID accountId, Money amount, Money balanceAfter, Instant createdAt) {}
