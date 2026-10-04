package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.shared.Money;
import java.util.Currency;
import java.util.UUID;

/** Outcome of a transfer. Every rejection leaves both wallets untouched. */
public sealed interface TransferResult {

    record Completed(TransferView transfer) implements TransferResult {}

    record InsufficientFunds(UUID walletId, Money available, Money requested) implements TransferResult {}

    record WalletNotFound(UUID walletId) implements TransferResult {}

    record SameWallet(UUID walletId) implements TransferResult {}

    record CurrencyMismatch(UUID walletId, Currency walletCurrency, Currency requestedCurrency)
            implements TransferResult {}
}
