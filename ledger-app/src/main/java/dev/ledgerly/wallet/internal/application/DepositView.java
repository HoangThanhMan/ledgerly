package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** A completed internal deposit into a wallet. Its id is the id of the ledger transaction. */
public record DepositView(UUID id, UUID walletId, Money amount, Instant createdAt) {}
