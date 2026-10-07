package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.DepositResult.Completed;
import dev.ledgerly.wallet.internal.application.DepositResult.CurrencyMismatch;
import dev.ledgerly.wallet.internal.application.DepositResult.WalletNotFound;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.web.IdempotentRequests.Reply;
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
@RestController
@RequestMapping("/v1/admin/deposits")
class DepositController {

    private final TransferService transfers;
    private final IdempotentRequests idempotent;

    DepositController(TransferService transfers, IdempotentRequests idempotent) {
        this.transfers = transfers;
        this.idempotent = idempotent;
    }

    @PostMapping
    ResponseEntity<String> create(
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
