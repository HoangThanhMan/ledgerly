package dev.ledgerly.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.PostingResult.AccountNotFound;
import dev.ledgerly.ledger.PostingResult.InsufficientFunds;
import dev.ledgerly.ledger.PostingResult.Posted;
import dev.ledgerly.shared.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerServiceIT extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");

    private final LedgerApi ledger;

    LedgerServiceIT(@Autowired LedgerApi ledger) {
        this.ledger = ledger;
    }

    @Test
    void openedAccountIsAnEmptyUserWallet() {
        AccountView account = ledger.openAccount(VND);

        assertThat(account.type()).isEqualTo(AccountType.USER_WALLET);
        assertThat(account.balance()).isEqualTo(vnd(0));
        assertThat(ledger.findAccount(account.id())).contains(account);
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThat(ledger.findAccount(UUID.randomUUID())).isEmpty();
    }

    @Test
    void systemAccountsAreResolvedByCode() {
        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);

        assertThat(ledger.findAccount(funding))
                .hasValueSatisfying(account -> assertThat(account.type()).isEqualTo(AccountType.SYSTEM));
    }

    @Test
    void postedTransferUpdatesBalancesAndRecordsEntries() {
        UUID source = fundedWallet(1_000);
        UUID target = ledger.openAccount(VND).id();

        PostingResult result = ledger.post(transfer(source, target, 300));

        assertThat(result).isInstanceOf(Posted.class);
        assertThat(balanceOf(source)).isEqualTo(vnd(700));
        assertThat(balanceOf(target)).isEqualTo(vnd(300));

        UUID transactionId = ((Posted) result).transactionId();
        TransactionView transaction = ledger.findTransaction(transactionId).orElseThrow();
        assertThat(transaction.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.entries())
                .extracting(EntryView::accountId, EntryView::amount, EntryView::balanceAfter)
                .containsExactly(tuple(source, vnd(-300), vnd(700)), tuple(target, vnd(300), vnd(300)));
    }

    @Test
    void rejectedPostingChangesNothing() {
        UUID source = fundedWallet(100);
        UUID target = ledger.openAccount(VND).id();

        PostingResult result = ledger.post(transfer(source, target, 101));

        assertThat(result).isEqualTo(new InsufficientFunds(source, vnd(100), vnd(101)));
        assertThat(balanceOf(source)).isEqualTo(vnd(100));
        assertThat(balanceOf(target)).isEqualTo(vnd(0));
        assertThat(ledger.history(source, null, 10)).hasSize(1);
        assertThat(ledger.history(target, null, 10)).isEmpty();
    }

    @Test
    void postingToAnUnknownAccountIsRejected() {
        UUID source = fundedWallet(100);
        UUID unknown = UUID.randomUUID();

        assertThat(ledger.post(transfer(source, unknown, 10))).isEqualTo(new AccountNotFound(unknown));
    }

    @Test
    void historyIsNewestFirstAndPagesWithoutGapsOrDuplicates() {
        UUID wallet = ledger.openAccount(VND).id();
        for (int i = 1; i <= 5; i++) {
            deposit(wallet, i);
        }

        List<EntryView> pages = new ArrayList<>();
        @Nullable Long cursor = null;
        List<EntryView> page;
        do {
            page = ledger.history(wallet, cursor, 2);
            pages.addAll(page);
            cursor = page.isEmpty() ? null : page.getLast().id();
        } while (page.size() == 2);

        assertThat(pages).extracting(EntryView::amount).containsExactly(vnd(5), vnd(4), vnd(3), vnd(2), vnd(1));
        assertThat(pages).extracting(EntryView::id).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    private UUID fundedWallet(long amount) {
        UUID wallet = ledger.openAccount(VND).id();
        deposit(wallet, amount);
        return wallet;
    }

    private void deposit(UUID wallet, long amount) {
        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);
        PostingResult result = ledger.post(new PostingRequest(
                TransactionType.DEPOSIT,
                null,
                List.of(new Posting(funding, vnd(-amount)), new Posting(wallet, vnd(amount)))));
        assertThat(result).isInstanceOf(Posted.class);
    }

    private static PostingRequest transfer(UUID source, UUID target, long amount) {
        return new PostingRequest(
                TransactionType.TRANSFER,
                null,
                List.of(new Posting(source, vnd(-amount)), new Posting(target, vnd(amount))));
    }

    private Money balanceOf(UUID account) {
        return ledger.findAccount(account).orElseThrow().balance();
    }

    private static Money vnd(long amount) {
        return Money.of(amount, VND);
    }
}
