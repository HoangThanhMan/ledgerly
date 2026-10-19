package dev.ledgerly.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A ledger transaction with its entries in the order they were written. */
public record TransactionView(
        UUID id, TransactionType type, @Nullable String reference, Instant createdAt, List<EntryView> entries) {

    public TransactionView {
        entries = List.copyOf(entries);
    }
}
