package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.ledger.AccountView;
import java.util.Currency;

/** Outcome of opening a wallet. */
public sealed interface OpenWalletResult {

    record Opened(AccountView wallet) implements OpenWalletResult {}

    record UnsupportedCurrency(Currency currency) implements OpenWalletResult {}
}
