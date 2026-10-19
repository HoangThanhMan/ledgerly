package dev.ledgerly.ledger;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** Outcome of posting a ledger transaction. Business rejections are values, not exceptions. */
public sealed interface PostingResult {

    record Posted(UUID transactionId, Instant createdAt) implements PostingResult {}

    /** The transaction was not written. Nothing changed in the ledger. */
    sealed interface Rejected extends PostingResult {}

    record AccountNotFound(UUID accountId) implements Rejected {}

    record CurrencyMismatch(UUID accountId, Currency accountCurrency, Currency postingCurrency) implements Rejected {}

    record InsufficientFunds(UUID accountId, Money available, Money requested) implements Rejected {}

    /** The posting would take the balance of this account past what the ledger can hold in a 64-bit integer. */
    record BalanceLimitExceeded(UUID accountId) implements Rejected {}
}
