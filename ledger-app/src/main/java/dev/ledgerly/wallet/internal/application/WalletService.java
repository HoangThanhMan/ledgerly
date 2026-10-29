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

/**
 * A wallet is a ledger account of type {@code USER_WALLET}. System accounts are never exposed as wallets.
 *
 * <p>A wallet belongs to the user who opened it. Only its owner may read it or spend from it: to anyone else it is
 * absent, exactly like a wallet that does not exist, so the API does not reveal which wallet ids are in use.
 */
@Service
public class WalletService {

    // System accounts are seeded in VND only, so a wallet in another currency could never receive money.
    private static final Set<Currency> SUPPORTED_CURRENCIES = Set.of(Currency.getInstance("VND"));

    private final LedgerApi ledger;

    WalletService(LedgerApi ledger) {
        this.ledger = ledger;
    }

    public OpenWalletResult open(Currency currency, UUID ownerId) {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            return new UnsupportedCurrency(currency);
        }
        return new Opened(ledger.openAccount(currency, ownerId));
    }

    /** The wallet, whoever owns it. Only for paying into it: reading it is for its owner. */
    public Optional<AccountView> find(UUID walletId) {
        return ledger.findAccount(walletId).filter(account -> account.type() == AccountType.USER_WALLET);
    }

    /** The wallet, if it exists and belongs to {@code ownerId}. */
    public Optional<AccountView> findOwned(UUID walletId, UUID ownerId) {
        return find(walletId).filter(wallet -> ownerId.equals(wallet.ownerId()));
    }

    /** The wallets of a user, oldest first. */
    public List<AccountView> owned(UUID ownerId) {
        return ledger.accountsOwnedBy(ownerId);
    }

    /**
     * A page of the wallet's entries, newest first, or empty if the wallet does not exist or is not the owner's.
     *
     * @param after id of the last entry of the previous page, or {@code null} for the first page
     */
    public Optional<EntryPage> history(UUID walletId, UUID ownerId, @Nullable Long after, int limit) {
        if (findOwned(walletId, ownerId).isEmpty()) {
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
