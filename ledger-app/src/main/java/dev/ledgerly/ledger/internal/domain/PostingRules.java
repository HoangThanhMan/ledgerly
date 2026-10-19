package dev.ledgerly.ledger.internal.domain;

import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingResult.AccountNotFound;
import dev.ledgerly.ledger.PostingResult.BalanceLimitExceeded;
import dev.ledgerly.ledger.PostingResult.CurrencyMismatch;
import dev.ledgerly.ledger.PostingResult.InsufficientFunds;
import dev.ledgerly.shared.Money;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides whether a set of postings can be applied to the current account balances.
 *
 * <p>Malformed postings (fewer than two, a zero amount, the same account twice, or not summing to zero) are
 * programming errors and throw {@link IllegalArgumentException}. Conditions a client can cause (unknown account,
 * wrong currency, not enough money, a balance past what a {@code long} can hold) are returned as
 * {@link PostingDecision.Rejected}.
 */
public final class PostingRules {

    private PostingRules() {}

    public static PostingDecision apply(List<Account> accounts, List<Posting> postings) {
        requireWellFormed(postings);
        Map<UUID, Account> byId = accounts.stream().collect(Collectors.toMap(Account::id, Function.identity()));

        List<EntryDraft> entries = new ArrayList<>(postings.size());
        for (Posting posting : postings) {
            Account account = byId.get(posting.accountId());
            if (account == null) {
                return new PostingDecision.Rejected(new AccountNotFound(posting.accountId()));
            }
            Money balance = account.balance();
            if (!balance.currency().equals(posting.amount().currency())) {
                return new PostingDecision.Rejected(new CurrencyMismatch(
                        account.id(), balance.currency(), posting.amount().currency()));
            }
            Money balanceAfter;
            try {
                balanceAfter = balance.plus(posting.amount());
            } catch (ArithmeticException overflow) {
                return new PostingDecision.Rejected(new BalanceLimitExceeded(account.id()));
            }
            if (balanceAfter.isNegative() && !account.allowNegative()) {
                return new PostingDecision.Rejected(new InsufficientFunds(
                        account.id(), balance, posting.amount().negate()));
            }
            entries.add(new EntryDraft(account.id(), posting.amount(), balanceAfter));
        }
        return new PostingDecision.Accepted(entries);
    }

    private static void requireWellFormed(List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("a transaction needs at least two postings, got " + postings.size());
        }
        Set<UUID> seen = new HashSet<>();
        Money sum = Money.zero(postings.getFirst().amount().currency());
        for (Posting posting : postings) {
            if (posting.amount().isZero()) {
                throw new IllegalArgumentException("posting to " + posting.accountId() + " has a zero amount");
            }
            if (!seen.add(posting.accountId())) {
                throw new IllegalArgumentException("account " + posting.accountId() + " is posted more than once");
            }
            sum = sum.plus(posting.amount());
        }
        if (!sum.isZero()) {
            throw new IllegalArgumentException("postings do not balance: they sum to " + sum);
        }
    }
}
