package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.Opened;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.UnsupportedCurrency;
import dev.ledgerly.wallet.internal.application.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiDocumentation.WALLETS, description = "Open wallets, read their balance and their entry history.")
@RestController
@RequestMapping("/v1/wallets")
class WalletController {

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @Operation(operationId = "openWallet", summary = "Open a wallet", description = """
            Creates an empty wallet: a ledger account for a user, with a balance of 0. Only VND is supported, \
            every other currency is rejected with `unsupported-currency`. This call is not idempotent: sending it \
            twice opens two wallets.""")
    @ApiResponse(
            responseCode = "201",
            description = "The wallet was opened.",
            headers =
                    @Header(
                            name = "Location",
                            description = "Path of the new wallet.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            example = "/v1/wallets/01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10")))
    @ProblemResponses(ProblemType.UNSUPPORTED_CURRENCY)
    @PostMapping
    ResponseEntity<WalletResponse> open(@Valid @RequestBody OpenWalletRequest request) {
        return switch (wallets.open(request.currency())) {
            case Opened opened ->
                ResponseEntity.created(
                                URI.create("/v1/wallets/" + opened.wallet().id()))
                        .body(WalletResponse.from(opened.wallet()));
            case UnsupportedCurrency unsupported ->
                throw new ProblemException(
                        ProblemType.UNSUPPORTED_CURRENCY,
                        "Wallets in " + unsupported.currency() + " are not supported");
        };
    }

    @Operation(operationId = "getWallet", summary = "Read a wallet", description = """
            Returns the wallet with its current balance. The balance always equals the sum of the wallet's entries \
            and is never negative. System accounts are not wallets: the id of one answers `wallet-not-found`.""")
    @ApiResponse(responseCode = "200", description = "The wallet.")
    @ProblemResponses(ProblemType.WALLET_NOT_FOUND)
    @GetMapping("/{id}")
    WalletResponse get(@Parameter(description = ApiDocumentation.WALLET_ID) @PathVariable UUID id) {
        return wallets.find(id).map(WalletResponse::from).orElseThrow(() -> WalletProblems.walletNotFound(id));
    }

    /** Entries of the wallet, newest first, paged by keyset: pass {@code nextCursor} of a page as {@code after}. */
    @Operation(operationId = "listWalletEntries", summary = "List the entries of a wallet", description = """
            Returns the ledger entries of the wallet, newest first. Every completed transfer or deposit adds one \
            entry to each account it touches, and an entry is never changed or deleted, so this is the full \
            history of the balance.

            Pages are read by keyset, not by offset: take `nextCursor` from a page and pass it as `after` to get \
            the next, older page. `nextCursor` is null on the last page. Entries written while you page only \
            appear in front of the first page, so no page repeats or skips an entry.""")
    @ApiResponse(responseCode = "200", description = "One page of entries, newest first.")
    @ProblemResponses(ProblemType.WALLET_NOT_FOUND)
    @GetMapping("/{id}/entries")
    EntryPageResponse entries(
            @Parameter(description = ApiDocumentation.WALLET_ID) @PathVariable UUID id,
            @Parameter(
                            description = "Return only entries older than the entry with this id. Pass the"
                                    + " `nextCursor` of the previous page. Leave it out for the first page.",
                            example = "1042")
                    @RequestParam(required = false)
                    @Positive @Nullable Long after,
            @Parameter(description = "Largest number of entries in the page, from 1 to 100.")
                    @RequestParam(defaultValue = "50")
                    @Min(1) @Max(100) int limit) {
        return wallets.history(id, after, limit)
                .map(EntryPageResponse::from)
                .orElseThrow(() -> WalletProblems.walletNotFound(id));
    }
}
