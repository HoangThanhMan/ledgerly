package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.wallet.internal.application.DepositResult.Completed;
import dev.ledgerly.wallet.internal.application.DepositResult.CurrencyMismatch;
import dev.ledgerly.wallet.internal.application.DepositResult.WalletNotFound;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal deposits from the funding account: the way to put money into wallets in development and tests. */
@Tag(
        name = ApiDocumentation.DEPOSITS,
        description = "Put money into wallets from the system funding account. For development, tests and demos.")
@RestController
@RequestMapping("/v1/admin/deposits")
class DepositController {

    private final TransferService transfers;
    private final IdempotentRequests idempotent;

    DepositController(TransferService transfers, IdempotentRequests idempotent) {
        this.transfers = transfers;
        this.idempotent = idempotent;
    }

    @Operation(operationId = "createDeposit", summary = "Deposit money into a wallet", description = """
            Credits the wallet and debits the system funding account, which is allowed to go negative, in one \
            database transaction. This is how wallets get money in development, tests and demos: no bank is \
            involved, and since the API has no authentication the endpoint must not be reachable from outside.

            The call needs an `Idempotency-Key`, with the same rules as a transfer: a retry with the same key \
            returns the stored response with `Idempotent-Replayed: true` and credits nothing again. The response \
            has no `Location`: a deposit cannot be read back on its own, it appears as an entry of the wallet.""")
    @ApiResponse(
            responseCode = "201",
            description = "The deposit is complete. Also the answer when a completed deposit is retried with the"
                    + " same Idempotency-Key.",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = DepositResponse.class)),
            headers =
                    @Header(
                            name = IdempotentRequests.REPLAYED_HEADER,
                            description = ApiDocumentation.REPLAYED,
                            schema = @Schema(type = "string", allowableValues = "true")))
    @ProblemResponses({
        ProblemType.WALLET_NOT_FOUND,
        ProblemType.IDEMPOTENCY_IN_PROGRESS,
        ProblemType.IDEMPOTENCY_KEY_REUSED,
        ProblemType.CURRENCY_MISMATCH,
        ProblemType.OVERLOADED
    })
    @PostMapping
    ResponseEntity<String> create(
            @Parameter(description = ApiDocumentation.IDEMPOTENCY_KEY, example = "9b2e4c1a-7d3f-4a6b-8c5d-0e1f2a3b4c5d")
                    @RequestHeader(ApiFormats.IDEMPOTENCY_KEY_HEADER)
                    @Pattern(regexp = ApiFormats.IDEMPOTENCY_KEY_PATTERN, message = ApiFormats.IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            HttpServletRequest http) {
        // A deposit cannot be read back on its own, so the response has no Location.
        return idempotent.execute(idempotencyKey, http, request, null, () -> deposit(request));
    }

    private Reply deposit(DepositRequest request) {
        return switch (transfers.deposit(request.walletId(), request.money())) {
            case Completed completed ->
                new Reply.Created(completed.deposit().id(), DepositResponse.from(completed.deposit()));
            case WalletNotFound notFound -> new Reply.Rejected(WalletProblems.walletNotFound(notFound.walletId()));
            case CurrencyMismatch mismatch ->
                new Reply.Rejected(WalletProblems.currencyMismatch(
                        mismatch.walletId(), mismatch.walletCurrency(), mismatch.requestedCurrency()));
        };
    }
}
