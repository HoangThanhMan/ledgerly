package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.ledger.EntryView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** {@code amount} is signed from the wallet's side: negative is money out, positive is money in. */
@Schema(description = "One ledger entry of a wallet: a single change of its balance.")
record EntryResponse(
        @Schema(
                description =
                        "Id of the entry, an integer sent as a string. Newer entries have larger ids. Usable as `after` when paging.",
                example = "1042")
        String id,

        @Schema(
                description = "Id of the transfer or deposit that wrote the entry.",
                example = "01a11ad9-7f10-7459-a273-deb24dbb4296")
        UUID transactionId,

        @Schema(
                description =
                        "Change of the balance, as a string holding a signed integer in the minor unit of the currency: negative is money out, positive is money in. Never 0.",
                example = "-150000")
        String amount,

        @Schema(
                description =
                        "Balance of the wallet right after this entry, in the same format as the balance of a wallet.",
                example = "350000")
        String balanceAfter,

        @Schema(description = "ISO 4217 code of the wallet's currency.", example = "VND")
        String currency,

        @Schema(description = "When the entry was written.") Instant createdAt) {

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
