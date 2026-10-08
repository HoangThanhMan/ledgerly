package dev.ledgerly.wallet.internal.application;

import dev.ledgerly.contracts.Topics;
import dev.ledgerly.contracts.TransferCompleted;
import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.EntryView;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingRequest;
import dev.ledgerly.ledger.PostingResult;
import dev.ledgerly.ledger.SystemAccount;
import dev.ledgerly.ledger.TransactionType;
import dev.ledgerly.ledger.TransactionView;
import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.shared.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Moves money between wallets, and into a wallet from the funding account.
 *
 * <p>Both parties are checked to be user wallets before posting: the ledger itself would let a system account that
 * may go negative pay out without limit. Whether the source can afford the amount is left to the ledger, which
 * decides and writes in one database transaction.
 *
 * <p>A completed transfer is announced with a {@code TransferCompleted} event, written to the outbox in the same
 * transaction as the ledger entries. Deposits are internal seeding and announce nothing.
 */
@Service
public class TransferService {

    private final LedgerApi ledger;
    private final WalletService wallets;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    TransferService(LedgerApi ledger, WalletService wallets, OutboxWriter outbox, MeterRegistry meters) {
        this.ledger = ledger;
        this.wallets = wallets;
        this.outbox = outbox;
        this.meters = meters;
    }

    /** Transactional so that the ledger entries and the event commit together, whoever the caller is. */
    @Transactional
    public TransferResult transfer(UUID sourceWalletId, UUID targetWalletId, Money amount) {
        TransferResult result = decide(sourceWalletId, targetWalletId, amount);
        count(result);
        return result;
    }

    /**
     * Counts what the service decided, once the transaction it decided in has committed. A decision that is rolled
     * back afterwards, for example because the request lost its idempotency lease, changed nothing and is not
     * counted. A request answered from its idempotency key never gets here, so a retry is not counted twice. A
     * failure that throws is not counted either: it shows up as a 5xx in the HTTP metrics.
     */
    private void count(TransferResult result) {
        String outcome = switch (result) {
            case TransferResult.Completed completed -> "completed";
            case TransferResult.InsufficientFunds funds -> "insufficient_funds";
            case TransferResult.WalletNotFound notFound -> "wallet_not_found";
            case TransferResult.SameWallet same -> "same_wallet";
            case TransferResult.CurrencyMismatch mismatch -> "currency_mismatch";
        };
        Counter counter = Counter.builder("ledgerly.transfers")
                .description("Transfers the service decided, by outcome")
                .tag("outcome", outcome)
                .register(meters);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }

    private TransferResult decide(UUID sourceWalletId, UUID targetWalletId, Money amount) {
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
            case PostingResult.Posted posted -> {
                outbox.append(new OutboxEvent(
                        Topics.TRANSFERS,
                        TransferCompleted.AGGREGATE_TYPE,
                        posted.transactionId(),
                        TransferCompleted.EVENT_TYPE,
                        TransferCompleted.VERSION,
                        new TransferCompleted(
                                sourceWalletId,
                                targetWalletId,
                                Long.toString(amount.amount()),
                                amount.currency().getCurrencyCode())));
                yield new TransferResult.Completed(new TransferView(
                        posted.transactionId(), sourceWalletId, targetWalletId, amount, posted.createdAt()));
            }
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
