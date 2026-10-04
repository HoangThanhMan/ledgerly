package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.wallet.internal.application.TransferResult.Completed;
import dev.ledgerly.wallet.internal.application.TransferResult.CurrencyMismatch;
import dev.ledgerly.wallet.internal.application.TransferResult.InsufficientFunds;
import dev.ledgerly.wallet.internal.application.TransferResult.SameWallet;
import dev.ledgerly.wallet.internal.application.TransferResult.WalletNotFound;
import dev.ledgerly.wallet.internal.application.TransferService;
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

@RestController
@RequestMapping("/v1/transfers")
class TransferController {

    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    ResponseEntity<TransferResponse> create(
            @RequestHeader(ApiFormats.IDEMPOTENCY_KEY_HEADER)
                    @Pattern(regexp = ApiFormats.IDEMPOTENCY_KEY_PATTERN, message = ApiFormats.IDEMPOTENCY_KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return switch (transfers.transfer(request.sourceWalletId(), request.targetWalletId(), request.money())) {
            case Completed completed ->
                ResponseEntity.created(URI.create(
                                "/v1/transfers/" + completed.transfer().id()))
                        .body(TransferResponse.from(completed.transfer()));
            case InsufficientFunds funds ->
                throw new ProblemException(
                        ProblemType.INSUFFICIENT_FUNDS,
                        "Wallet " + funds.walletId() + " has " + funds.available() + ", the transfer needs "
                                + funds.requested());
            case WalletNotFound notFound -> throw WalletProblems.walletNotFound(notFound.walletId());
            case SameWallet same ->
                throw new ProblemException(
                        ProblemType.SAME_ACCOUNT_TRANSFER, "Source and target are both wallet " + same.walletId());
            case CurrencyMismatch mismatch ->
                throw WalletProblems.currencyMismatch(
                        mismatch.walletId(), mismatch.walletCurrency(), mismatch.requestedCurrency());
        };
    }

    @GetMapping("/{id}")
    TransferResponse get(@PathVariable UUID id) {
        return transfers
                .find(id)
                .map(TransferResponse::from)
                .orElseThrow(() ->
                        new ProblemException(ProblemType.TRANSFER_NOT_FOUND, "Transfer " + id + " does not exist"));
    }
}
