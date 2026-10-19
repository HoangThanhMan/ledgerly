package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.EntryView;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.Opened;
import dev.ledgerly.wallet.internal.application.OpenWalletResult.UnsupportedCurrency;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** A wallet is a ledger account of type {@code USER_WALLET}. System accounts are never exposed as wallets. */
@Service
public class WalletService {

    // System accounts are seeded in VND only, so a wallet in another currency could never receive money.
    private static final Set<Currency> SUPPORTED_CURRENCIES = Set.of(Currency.getInstance("VND"));

    private final LedgerApi ledger;

    WalletService(LedgerApi ledger) {
        this.ledger = ledger;
    }

    public OpenWalletResult open(Currency currency) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            return new UnsupportedCurrency(currency);
        }
        return new Opened(ledger.openAccount(currency));
    }

    public Optional<AccountView> find(UUID walletId) {
        return ledger.findAccount(walletId).filter(account -> account.type() == AccountType.USER_WALLET);
    }

    /**
     * A page of the wallet's entries, newest first, or empty if the wallet does not exist.
     *
     * @param after id of the last entry of the previous page, or {@code null} for the first page
     */
    public Optional<EntryPage> history(UUID walletId, @Nullable Long after, int limit) {
        if (find(walletId).isEmpty()) {
            return Optional.empty();
        }
        // One entry more than asked for tells whether another page follows, without a second query.
        List<EntryView> entries = ledger.history(walletId, after, limit + 1);
        if (entries.size() <= limit) {
            return Optional.of(new EntryPage(entries, null));
        }
        List<EntryView> page = entries.subList(0, limit);
        return Optional.of(new EntryPage(page, page.getLast().id()));
    }
}
