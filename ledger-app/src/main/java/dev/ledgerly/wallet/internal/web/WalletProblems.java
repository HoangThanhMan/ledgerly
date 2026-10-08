package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemType;
import java.util.Currency;
import java.util.UUID;

/** Problems that more than one wallet endpoint reports. */
final class WalletProblems {

    private WalletProblems() {}

    static ProblemException walletNotFound(UUID walletId) {
        return new ProblemException(ProblemType.WALLET_NOT_FOUND, "Wallet " + walletId + " does not exist");
    }

    static ProblemException balanceLimitExceeded(UUID walletId) {
        return new ProblemException(
                ProblemType.BALANCE_LIMIT_EXCEEDED,
                "Wallet " + walletId + " cannot receive this amount: a balance would exceed the largest value the"
                        + " ledger can hold");
    }

    static ProblemException currencyMismatch(UUID walletId, Currency walletCurrency, Currency requestedCurrency) {
        return new ProblemException(
                ProblemType.CURRENCY_MISMATCH,
                "Wallet " + walletId + " holds " + walletCurrency + ", the request is in " + requestedCurrency);
    }
}
