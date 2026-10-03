package dev.ledgerly.ledger.internal.application;

import dev.ledgerly.ledger.AccountView;
import dev.ledgerly.ledger.EntryView;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingRequest;
import dev.ledgerly.ledger.PostingResult;
import dev.ledgerly.ledger.SystemAccount;
import dev.ledgerly.ledger.TransactionView;
import dev.ledgerly.ledger.internal.domain.EntryDraft;
import dev.ledgerly.ledger.internal.domain.PostingDecision;
import dev.ledgerly.ledger.internal.domain.PostingRules;
import dev.ledgerly.ledger.internal.persistence.AccountRepository;
import dev.ledgerly.ledger.internal.persistence.EntryRepository;
import dev.ledgerly.ledger.internal.persistence.TransactionRepository;
import dev.ledgerly.ledger.internal.persistence.TransactionRepository.TransactionRow;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService implements LedgerApi {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    @Override
    @Transactional
    public AccountView openAccount(Currency currency) {
        return accounts.insertUserWallet(currency);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> findAccount(UUID accountId) {
        return accounts.findView(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public UUID systemAccountId(SystemAccount account) {
        return accounts.findIdByCode(account.code())
                .orElseThrow(() -> new IllegalStateException("system account " + account.code() + " is not seeded"));
    }

    @Override
    @Transactional
    public PostingResult post(PostingRequest request) {
        List<UUID> ids = request.postings().stream().map(Posting::accountId).toList();
        return switch (PostingRules.apply(accounts.findForPosting(ids), request.postings())) {
            case PostingDecision.Rejected rejected -> rejected.reason();
            case PostingDecision.Accepted accepted -> write(request, accepted.entries());
        };
    }

    private PostingResult.Posted write(PostingRequest request, List<EntryDraft> drafts) {
        TransactionRow transaction = transactions.insert(request.type(), request.reference());
        entries.insertAll(transaction.id(), drafts);
        for (EntryDraft draft : drafts) {
            accounts.updateBalance(draft.accountId(), draft.balanceAfter().amount());
        }
        return new PostingResult.Posted(transaction.id(), transaction.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntryView> history(UUID accountId, @Nullable Long olderThan, int limit) {
        return entries.findByAccountOlderThan(accountId, olderThan == null ? Long.MAX_VALUE : olderThan, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransactionView> findTransaction(UUID transactionId) {
        return transactions
                .find(transactionId)
                .map(row -> new TransactionView(
                        row.id(), row.type(), row.reference(), row.createdAt(), entries.findByTransaction(row.id())));
    }
}
