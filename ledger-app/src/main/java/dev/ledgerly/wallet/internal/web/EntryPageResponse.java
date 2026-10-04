package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.EntryPage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param nextCursor value for the {@code after} parameter of the next page, or {@code null} on the last page
 */
record EntryPageResponse(
        List<EntryResponse> items, @Nullable String nextCursor) {

    static EntryPageResponse from(EntryPage page) {
        Long nextCursor = page.nextCursor();
        return new EntryPageResponse(
                page.items().stream().map(EntryResponse::from).toList(),
                nextCursor == null ? null : nextCursor.toString());
    }
}
