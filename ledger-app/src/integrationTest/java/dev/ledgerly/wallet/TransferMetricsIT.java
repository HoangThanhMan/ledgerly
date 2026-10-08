package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/** {@code ledgerly.transfers} counts what became of each transfer the service decided, by outcome. */
class TransferMetricsIT extends AbstractIntegrationTest {

    private final TransferService transfers;
    private final MeterRegistry meters;
    private final ConcurrentTransfers support;
    private final WalletApiDriver api;

    TransferMetricsIT(
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired MeterRegistry meters,
            @Autowired RestTestClient client) {
        this.transfers = transfers;
        this.meters = meters;
        this.support = new ConcurrentTransfers(wallets, transfers);
        this.api = new WalletApiDriver(client);
    }

    @Test
    void everyOutcomeIsCountedUnderItsOwnTag() {
        UUID source = support.openWallet(100);
        UUID target = support.openWallet(0);
        double completed = count("completed");
        double insufficientFunds = count("insufficient_funds");
        double sameWallet = count("same_wallet");
        double walletNotFound = count("wallet_not_found");
        double currencyMismatch = count("currency_mismatch");

        support.transfer(source, target, 60);
        support.transfer(source, target, 30);
        support.transfer(source, target, 500);
        support.transfer(source, source, 1);
        support.transfer(source, UUID.randomUUID(), 1);
        transfers.transfer(source, target, Money.of(1, Currency.getInstance("USD")));

        assertThat(count("completed") - completed).isEqualTo(2);
        assertThat(count("insufficient_funds") - insufficientFunds).isEqualTo(1);
        assertThat(count("same_wallet") - sameWallet).isEqualTo(1);
        assertThat(count("wallet_not_found") - walletNotFound).isEqualTo(1);
        assertThat(count("currency_mismatch") - currencyMismatch).isEqualTo(1);
    }

    @Test
    void replayedRequestIsNotCountedAsAnotherTransfer() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = UUID.randomUUID().toString();
        double completed = count("completed");

        api.transfer(key, source, target, "10").expectStatus().isCreated();
        api.transfer(key, source, target, "10").expectStatus().isCreated();
        api.transfer(key, source, target, "10").expectStatus().isCreated();

        assertThat(count("completed") - completed).isEqualTo(1);
    }

    @Test
    void depositsAreNotCountedAsTransfers() {
        double before = meters.find("ledgerly.transfers").counters().stream()
                .mapToDouble(Counter::count)
                .sum();

        support.openWallet(100);

        assertThat(meters.find("ledgerly.transfers").counters().stream()
                        .mapToDouble(Counter::count)
                        .sum())
                .isEqualTo(before);
    }

    private double count(String outcome) {
        Counter counter =
                meters.find("ledgerly.transfers").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }
}
