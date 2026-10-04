package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.UUID;

/** A completed transfer between two wallets. Its id is the id of the ledger transaction. */
public record TransferView(UUID id, UUID sourceWalletId, UUID targetWalletId, Money amount, Instant createdAt) {}
