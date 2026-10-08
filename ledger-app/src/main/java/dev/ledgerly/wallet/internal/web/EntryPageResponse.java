package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.EntryPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * @param nextCursor value for the {@code after} parameter of the next page, or {@code null} on the last page
 */
@Schema(description = "One page of the entries of a wallet.")
record EntryPageResponse(
        @Schema(
                description =
                        "The entries of this page, newest first. Empty when the wallet has no entries, or none older than `after`.")
        List<EntryResponse> items,

        @Schema(
                description = "Pass as `after` to read the next, older page. Null when this is the last page.",
                example = "1041",
                nullable = true)
        @Nullable String nextCursor) {

    static EntryPageResponse from(EntryPage page) {
        Long nextCursor = page.nextCursor();
        return new EntryPageResponse(
                page.items().stream().map(EntryResponse::from).toList(),
                nextCursor == null ? null : nextCursor.toString());
    }
}
