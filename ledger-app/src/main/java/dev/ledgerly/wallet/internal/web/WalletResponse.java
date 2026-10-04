package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.ledger.AccountView;
import java.time.Instant;
import java.util.UUID;

record WalletResponse(UUID id, String currency, String balance, Instant createdAt) {

    static WalletResponse from(AccountView wallet) {
        return new WalletResponse(
                wallet.id(),
                ApiFormats.currency(wallet.balance()),
                ApiFormats.amount(wallet.balance()),
                wallet.createdAt());
    }
}
