package dev.ledgerly.ledger.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingResult.AccountNotFound;
import dev.ledgerly.ledger.PostingResult.BalanceLimitExceeded;
import dev.ledgerly.ledger.PostingResult.CurrencyMismatch;
import dev.ledgerly.ledger.PostingResult.InsufficientFunds;
import dev.ledgerly.ledger.internal.domain.PostingDecision.Accepted;
import dev.ledgerly.ledger.internal.domain.PostingDecision.Rejected;
import dev.ledgerly.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");

    private final Account source = wallet(1_000);
    private final Account target = wallet(0);

    @Test
    void sufficientFundsAreAcceptedWithBalancesAfterEachEntry() {
        PostingDecision decision = PostingRules.apply(List.of(source, target), transfer(source, target, 100));

        assertThat(decision)
                .isEqualTo(new Accepted(List.of(
                        new EntryDraft(source.id(), vnd(-100), vnd(900)),
                        new EntryDraft(target.id(), vnd(100), vnd(100)))));
    }

    @Test
    void spendingTheWholeBalanceIsAccepted() {
        PostingDecision decision = PostingRules.apply(List.of(source, target), transfer(source, target, 1_000));

        assertThat(decision).isInstanceOf(Accepted.class);
    }

    @Test
    void insufficientFundsAreRejected() {
        PostingDecision decision = PostingRules.apply(List.of(source, target), transfer(source, target, 1_001));

        assertThat(decision).isEqualTo(new Rejected(new InsufficientFunds(source.id(), vnd(1_000), vnd(1_001))));
    }

    @Test
    void accountThatAllowsNegativeBalanceMayGoBelowZero() {
        Account funding = new Account(UUID.randomUUID(), vnd(0), true);

        PostingDecision decision = PostingRules.apply(List.of(funding, target), transfer(funding, target, 500));

        assertThat(decision)
                .isEqualTo(new Accepted(List.of(
                        new EntryDraft(funding.id(), vnd(-500), vnd(-500)),
                        new EntryDraft(target.id(), vnd(500), vnd(500)))));
    }

    @Test
    void unknownAccountIsRejected() {
        PostingDecision decision = PostingRules.apply(List.of(source), transfer(source, target, 100));

        assertThat(decision).isEqualTo(new Rejected(new AccountNotFound(target.id())));
    }

    @Test
    void postingInAnotherCurrencyThanTheAccountIsRejected() {
        List<Posting> postings =
                List.of(new Posting(source.id(), Money.of(-100, USD)), new Posting(target.id(), Money.of(100, USD)));

        PostingDecision decision = PostingRules.apply(List.of(source, target), postings);

        assertThat(decision).isEqualTo(new Rejected(new CurrencyMismatch(source.id(), VND, USD)));
    }

    @Test
    void unbalancedPostingsAreRefused() {
        List<Posting> postings = List.of(new Posting(source.id(), vnd(-100)), new Posting(target.id(), vnd(99)));

        assertThatThrownBy(() -> PostingRules.apply(List.of(source, target), postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("balance");
    }

    @Test
    void postingsInMixedCurrenciesAreRefused() {
        List<Posting> postings =
                List.of(new Posting(source.id(), vnd(-100)), new Posting(target.id(), Money.of(100, USD)));

        assertThatThrownBy(() -> PostingRules.apply(List.of(source, target), postings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fewerThanTwoPostingsAreRefused() {
        List<Posting> postings = List.of(new Posting(source.id(), vnd(0)));

        assertThatThrownBy(() -> PostingRules.apply(List.of(source), postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void zeroAmountPostingIsRefused() {
        List<Posting> postings = List.of(
                new Posting(source.id(), vnd(-100)),
                new Posting(target.id(), vnd(100)),
                new Posting(UUID.randomUUID(), vnd(0)));

        assertThatThrownBy(() -> PostingRules.apply(List.of(source, target), postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
    }

    @Test
    void postingTwiceToTheSameAccountIsRefused() {
        List<Posting> postings = List.of(new Posting(source.id(), vnd(-100)), new Posting(source.id(), vnd(100)));

        assertThatThrownBy(() -> PostingRules.apply(List.of(source), postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void creditThatWouldOverflowTheBalanceIsRejected() {
        Account full = new Account(UUID.randomUUID(), vnd(Long.MAX_VALUE), false);

        PostingDecision decision = PostingRules.apply(List.of(source, full), transfer(source, full, 1));

        assertThat(decision).isEqualTo(new Rejected(new BalanceLimitExceeded(full.id())));
    }

    @Test
    void debitThatWouldUnderflowAnAccountAllowedToGoNegativeIsRejected() {
        Account overdrawn = new Account(UUID.randomUUID(), vnd(Long.MIN_VALUE), true);

        PostingDecision decision = PostingRules.apply(List.of(overdrawn, target), transfer(overdrawn, target, 1));

        assertThat(decision).isEqualTo(new Rejected(new BalanceLimitExceeded(overdrawn.id())));
    }

    private static Account wallet(long balance) {
        return new Account(UUID.randomUUID(), vnd(balance), false);
    }

    private static List<Posting> transfer(Account from, Account to, long amount) {
        return List.of(new Posting(from.id(), vnd(-amount)), new Posting(to.id(), vnd(amount)));
    }

    private static Money vnd(long amount) {
        return Money.of(amount, VND);
    }
}
