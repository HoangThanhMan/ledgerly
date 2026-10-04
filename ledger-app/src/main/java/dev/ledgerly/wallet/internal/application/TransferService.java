package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.EntryView;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingRequest;
import dev.ledgerly.ledger.PostingResult;
import dev.ledgerly.ledger.SystemAccount;
import dev.ledgerly.ledger.TransactionType;
import dev.ledgerly.ledger.TransactionView;
import dev.ledgerly.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Moves money between wallets, and into a wallet from the funding account.
 *
 * <p>Both parties are checked to be user wallets before posting: the ledger itself would let a system account that
 * may go negative pay out without limit. Whether the source can afford the amount is left to the ledger, which
 * decides and writes in one database transaction.
 */
@Service
public class TransferService {

    private final LedgerApi ledger;
    private final WalletService wallets;

    TransferService(LedgerApi ledger, WalletService wallets) {
        this.ledger = ledger;
        this.wallets = wallets;
    }

    public TransferResult transfer(UUID sourceWalletId, UUID targetWalletId, Money amount) {
        if (sourceWalletId.equals(targetWalletId)) {
            return new TransferResult.SameWallet(sourceWalletId);
        }
        for (UUID walletId : List.of(sourceWalletId, targetWalletId)) {
            Optional<AccountView> wallet = wallets.find(walletId);
            if (wallet.isEmpty()) {
                return new TransferResult.WalletNotFound(walletId);
            }
            Currency walletCurrency = wallet.get().balance().currency();
            if (!walletCurrency.equals(amount.currency())) {
                return new TransferResult.CurrencyMismatch(walletId, walletCurrency, amount.currency());
            }
        }

        PostingRequest request = new PostingRequest(
                TransactionType.TRANSFER,
                null,
                List.of(new Posting(sourceWalletId, amount.negate()), new Posting(targetWalletId, amount)));
        return switch (ledger.post(request)) {
            case PostingResult.Posted posted ->
                new TransferResult.Completed(new TransferView(
                        posted.transactionId(), sourceWalletId, targetWalletId, amount, posted.createdAt()));
            case PostingResult.InsufficientFunds funds ->
                new TransferResult.InsufficientFunds(funds.accountId(), funds.available(), funds.requested());
            case PostingResult.AccountNotFound notFound -> new TransferResult.WalletNotFound(notFound.accountId());
            case PostingResult.CurrencyMismatch mismatch ->
                new TransferResult.CurrencyMismatch(
                        mismatch.accountId(), mismatch.accountCurrency(), mismatch.postingCurrency());
        };
    }

    public DepositResult deposit(UUID walletId, Money amount) {
        Optional<AccountView> wallet = wallets.find(walletId);
        if (wallet.isEmpty()) {
            return new DepositResult.WalletNotFound(walletId);
        }
        Currency walletCurrency = wallet.get().balance().currency();
        if (!walletCurrency.equals(amount.currency())) {
            return new DepositResult.CurrencyMismatch(walletId, walletCurrency, amount.currency());
        }

        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);
        PostingRequest request = new PostingRequest(
                TransactionType.DEPOSIT,
                null,
                List.of(new Posting(funding, amount.negate()), new Posting(walletId, amount)));
        return switch (ledger.post(request)) {
            case PostingResult.Posted posted ->
                new DepositResult.Completed(
                        new DepositView(posted.transactionId(), walletId, amount, posted.createdAt()));
            case PostingResult.AccountNotFound notFound -> new DepositResult.WalletNotFound(notFound.accountId());
            case PostingResult.CurrencyMismatch mismatch ->
                new DepositResult.CurrencyMismatch(
                        mismatch.accountId(), mismatch.accountCurrency(), mismatch.postingCurrency());
            case PostingResult.InsufficientFunds funds ->
                throw new IllegalStateException(
                        "the funding account may go negative, yet the ledger reported " + funds);
        };
    }

    /** The transfer with this id, or empty if there is none or the ledger transaction is of another type. */
    public Optional<TransferView> find(UUID transferId) {
        return ledger.findTransaction(transferId)
                .filter(transaction -> transaction.type() == TransactionType.TRANSFER)
                .map(TransferService::toTransfer);
    }

    private static TransferView toTransfer(TransactionView transaction) {
        EntryView debit = entry(transaction, true);
        EntryView credit = entry(transaction, false);
        return new TransferView(
                transaction.id(), debit.accountId(), credit.accountId(), credit.amount(), transaction.createdAt());
    }

    private static EntryView entry(TransactionView transaction, boolean debit) {
        return transaction.entries().stream()
                .filter(entry -> entry.amount().isNegative() == debit)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "transfer " + transaction.id() + " has no " + (debit ? "debit" : "credit") + " entry"));
    }
}
