package dev.ledgerly.ledger;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Entry point of the ledger module. Other modules use only this interface and the types next to it. */
public interface LedgerApi {

    /** Opens an empty user wallet. */
    AccountView openAccount(Currency currency);

    Optional<AccountView> findAccount(UUID accountId);

    UUID systemAccountId(SystemAccount account);

    /**
     * Writes a balanced transaction atomically: one entry per posting and the new balances.
     *
     * @throws IllegalArgumentException if the postings are malformed (fewer than two, a zero amount, the same account
     *     twice, or a non-zero sum)
     */
    PostingResult post(PostingRequest request);

    /**
     * Entries of an account, newest first.
     *
     * @param olderThan id of the last entry of the previous page, or {@code null} for the first page
     */
    List<EntryView> history(UUID accountId, @Nullable Long olderThan, int limit);

    Optional<TransactionView> findTransaction(UUID transactionId);
}
