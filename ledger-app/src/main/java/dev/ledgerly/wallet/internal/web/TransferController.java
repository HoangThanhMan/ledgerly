package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.wallet.internal.application.TransferResult.Completed;
import dev.ledgerly.wallet.internal.application.TransferResult.CurrencyMismatch;
import dev.ledgerly.wallet.internal.application.TransferResult.InsufficientFunds;
import dev.ledgerly.wallet.internal.application.TransferResult.SameWallet;
import dev.ledgerly.wallet.internal.application.TransferResult.WalletNotFound;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.web.IdempotentRequests.Reply;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiDocumentation.TRANSFERS, description = "Move money between two wallets.")
@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    private final TransferService transfers;
    private final IdempotentRequests idempotent;

    TransferController(TransferService transfers, IdempotentRequests idempotent) {
        this.transfers = transfers;
        this.idempotent = idempotent;
    }

    @Operation(operationId = "createTransfer", summary = "Transfer money between two wallets", description = """
            Moves `amount` from the source wallet to the target wallet in a single database transaction: either \
            both balances change and two entries are written, or nothing changes at all. The transfer is complete \
            when the call returns 201, there is no pending state. A completed transfer also publishes a \
            `TransferCompleted` event.

            The call needs an `Idempotency-Key`. Sending the same request again with the same key never moves \
            the money twice: it returns the stored response of the first attempt with the header \
            `Idempotent-Replayed: true`. That holds for business rejections too: a transfer refused with \
            `insufficient-funds` stays refused under its key even after the wallet is topped up, so a new \
            attempt needs a new key.

            When the outcome is unknown, retry with the same key: after a lost connection, a 409, a 500 or a \
            503. In those cases nothing was stored and no money moved, or the first attempt is still running.""")
    @ApiResponse(
            responseCode = "201",
            description = "The transfer is complete. Also the answer when a completed transfer is retried with"
                    + " the same Idempotency-Key.",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = TransferResponse.class)),
            headers = {
                @Header(
                        name = "Location",
                        description = "Path of the transfer.",
                        schema =
                                @Schema(
                                        type = "string",
                                        example = "/v1/transfers/01a11ad9-7f10-7459-a273-deb24dbb4296")),
                @Header(
                        name = IdempotentRequests.REPLAYED_HEADER,
                        description = ApiDocumentation.REPLAYED,
                        schema = @Schema(type = "string", allowableValues = "true"))
            })
    @ProblemResponses({
        ProblemType.WALLET_NOT_FOUND,
        ProblemType.IDEMPOTENCY_IN_PROGRESS,
        ProblemType.IDEMPOTENCY_KEY_REUSED,
        ProblemType.INSUFFICIENT_FUNDS,
        ProblemType.SAME_ACCOUNT_TRANSFER,
        ProblemType.CURRENCY_MISMATCH,
        ProblemType.OVERLOADED
    })
    @PostMapping
    ResponseEntity<String> create(
            @Parameter(description = ApiDocumentation.IDEMPOTENCY_KEY, example = "6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f")
                    @RequestHeader(ApiFormats.IDEMPOTENCY_KEY_HEADER)
                    @Pattern(regexp = ApiFormats.IDEMPOTENCY_KEY_PATTERN, message = ApiFormats.IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            HttpServletRequest http) {
        return idempotent.execute(
                idempotencyKey, http, request, id -> URI.create("/v1/transfers/" + id), () -> transfer(request));
    }

    private Reply transfer(TransferRequest request) {
        return switch (transfers.transfer(request.sourceWalletId(), request.targetWalletId(), request.money())) {
            case Completed completed ->
                new Reply.Created(completed.transfer().id(), TransferResponse.from(completed.transfer()));
            case InsufficientFunds funds ->
                new Reply.Rejected(new ProblemException(
                        ProblemType.INSUFFICIENT_FUNDS,
                        "Wallet " + funds.walletId() + " has " + funds.available() + ", the transfer needs "
                                + funds.requested()));
            case WalletNotFound notFound -> new Reply.Rejected(WalletProblems.walletNotFound(notFound.walletId()));
            case SameWallet same ->
                new Reply.Rejected(new ProblemException(
                        ProblemType.SAME_ACCOUNT_TRANSFER, "Source and target are both wallet " + same.walletId()));
            case CurrencyMismatch mismatch ->
                new Reply.Rejected(WalletProblems.currencyMismatch(
                        mismatch.walletId(), mismatch.walletCurrency(), mismatch.requestedCurrency()));
        };
    }

    @Operation(operationId = "getTransfer", summary = "Read a transfer", description = """
            Returns a completed transfer. Only transfers have an address here: the id of a deposit answers \
            `transfer-not-found`.""")
    @ApiResponse(responseCode = "200", description = "The transfer.")
    @ProblemResponses(ProblemType.TRANSFER_NOT_FOUND)
    @GetMapping("/{id}")
    TransferResponse get(
            @Parameter(description = "Id of the transfer, as returned when it was made.") @PathVariable UUID id) {
        return transfers
                .find(id)
                .map(TransferResponse::from)
                .orElseThrow(() ->
                        new ProblemException(ProblemType.TRANSFER_NOT_FOUND, "Transfer " + id + " does not exist"));
    }
}
