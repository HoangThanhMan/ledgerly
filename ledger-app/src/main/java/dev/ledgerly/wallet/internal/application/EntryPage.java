package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.ledger.EntryView;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a wallet's entries, newest first.
 *
 * @param nextCursor the entry id to continue after, or {@code null} when this is the last page
 */
public record EntryPage(List<EntryView> items, @Nullable Long nextCursor) {

    public EntryPage {
        items = List.copyOf(items);
    }
}
