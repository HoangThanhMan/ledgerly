package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.Opened;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.UnsupportedCurrency;
import dev.ledgerly.wallet.internal.application.WalletService;
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

@RestController
@RequestMapping("/v1/wallets")
class WalletController {

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

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

    @GetMapping("/{id}")
    WalletResponse get(@PathVariable UUID id) {
        return wallets.find(id).map(WalletResponse::from).orElseThrow(() -> WalletProblems.walletNotFound(id));
    }

    /** Entries of the wallet, newest first, paged by keyset: pass {@code nextCursor} of a page as {@code after}. */
    @GetMapping("/{id}/entries")
    EntryPageResponse entries(
            @PathVariable UUID id,
            @RequestParam(required = false) @Positive @Nullable Long after,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return wallets.history(id, after, limit)
                .map(EntryPageResponse::from)
                .orElseThrow(() -> WalletProblems.walletNotFound(id));
    }
}
