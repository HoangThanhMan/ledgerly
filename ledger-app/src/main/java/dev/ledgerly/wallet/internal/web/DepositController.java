package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.wallet.internal.application.DepositResult.Completed;
import dev.ledgerly.wallet.internal.application.DepositResult.CurrencyMismatch;
import dev.ledgerly.wallet.internal.application.DepositResult.WalletNotFound;
import dev.ledgerly.wallet.internal.application.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Internal deposits from the funding account: the way to put money into wallets in development and tests. */
@RestController
@RequestMapping("/v1/admin/deposits")
class DepositController {

    private final TransferService transfers;

    DepositController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DepositResponse create(
            @RequestHeader(ApiFormats.IDEMPOTENCY_KEY_HEADER)
                    @Pattern(regexp = ApiFormats.IDEMPOTENCY_KEY_PATTERN, message = ApiFormats.IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        return switch (transfers.deposit(request.walletId(), request.money())) {
            case Completed completed -> DepositResponse.from(completed.deposit());
            case WalletNotFound notFound -> throw WalletProblems.walletNotFound(notFound.walletId());
            case CurrencyMismatch mismatch ->
                throw WalletProblems.currencyMismatch(
                        mismatch.walletId(), mismatch.walletCurrency(), mismatch.requestedCurrency());
        };
    }
}
