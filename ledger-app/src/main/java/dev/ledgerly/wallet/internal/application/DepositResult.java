package dev.ledgerly.wallet.internal.application;

import java.util.Currency;
import java.util.UUID;

/** Outcome of an internal deposit. Every rejection leaves the wallet untouched. */
public sealed interface DepositResult {

    record Completed(DepositView deposit) implements DepositResult {}

    record WalletNotFound(UUID walletId) implements DepositResult {}

    /** The wallet, or the funding account that pays for the deposit, cannot take the amount. */
    record BalanceLimitExceeded(UUID walletId) implements DepositResult {}

    record CurrencyMismatch(UUID walletId, Currency walletCurrency, Currency requestedCurrency)
            implements DepositResult {}
}
