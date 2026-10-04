package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.ledger.EntryView;
import java.time.Instant;
import java.util.UUID;

/** {@code amount} is signed from the wallet's side: negative is money out, positive is money in. */
record EntryResponse(
        String id, UUID transactionId, String amount, String balanceAfter, String currency, Instant createdAt) {

    static EntryResponse from(EntryView entry) {
        return new EntryResponse(
                Long.toString(entry.id()),
                entry.transactionId(),
                ApiFormats.amount(entry.amount()),
                ApiFormats.amount(entry.balanceAfter()),
                ApiFormats.currency(entry.amount()),
                entry.createdAt());
    }
}
