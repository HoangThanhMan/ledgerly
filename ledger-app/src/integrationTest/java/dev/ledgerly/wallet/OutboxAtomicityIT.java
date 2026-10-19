package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.body;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.wallet.ConcurrentTransfers.Outcome;
import dev.ledgerly.wallet.internal.application.DepositResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferResult.Completed;
import dev.ledgerly.wallet.internal.application.TransferResult.InsufficientFunds;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invariant I6, first half: a transfer and its event are written together or not at all. Whether the event then
 * reaches Kafka is the relay's business.
 */
class OutboxAtomicityIT extends AbstractIntegrationTest {

    private final TransferService transfers;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final ConcurrentTransfers support;
    private final WalletApiDriver api;

    OutboxAtomicityIT(
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired OutboxWriter outbox,
            @Autowired JdbcClient jdbc,
            @Autowired TransactionTemplate transaction,
            @Autowired RestTestClient client) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.support = new ConcurrentTransfers(wallets, transfers);
        this.api = new WalletApiDriver(client);
    }

    @Test
    void committedTransferHasExactlyOneEvent() {
        UUID source = support.openWallet(1_000);
        UUID target = support.openWallet(0);

        Completed completed = (Completed) support.transfer(source, target, 300);

        UUID transferId = completed.transfer().id();
        assertThat(eventsOf(transferId))
                .singleElement()
                .satisfies(event -> assertThat(event)
                        .containsEntry("topic", Topics.TRANSFERS)
                        .containsEntry("aggregate_type", "LedgerTransaction")
                        .containsEntry("event_type", "TransferCompleted")
                        .containsEntry("event_version", 1)
                        .containsEntry("source", source.toString())
                        .containsEntry("target", target.toString())
                        .containsEntry("amount", "300")
                        .containsEntry("currency", "VND")
                        .containsEntry("published_at", null)
                        .containsEntry("attempts", 0));
    }

    @Test
    void rolledBackTransferLeavesNoEvent() {
        UUID source = support.openWallet(1_000);
        UUID target = support.openWallet(0);

        UUID transferId = transaction.execute(status -> {
            Completed completed = (Completed) support.transfer(source, target, 300);
            // Inside the transaction both are there. Then something later in it fails.
            assertThat(eventsOf(completed.transfer().id())).hasSize(1);
            status.setRollbackOnly();
            return completed.transfer().id();
        });

        assertThat(transferId).isNotNull();
        assertThat(transfers.find(transferId)).isEmpty();
        assertThat(eventsOf(transferId)).isEmpty();
        assertThat(support.balanceOf(source)).isEqualTo(1_000);
    }

    @Test
    void rejectedTransferHasNoEvent() {
        UUID source = support.openWallet(100);
        UUID target = support.openWallet(0);

        assertThat(support.transfer(source, target, 101)).isInstanceOf(InsufficientFunds.class);

        assertThat(eventsAboutWallet(target)).isZero();
    }

    @Test
    void depositHasNoEvent() {
        UUID wallet = support.openWallet(0);

        DepositResult.Completed deposit =
                (DepositResult.Completed) transfers.deposit(wallet, ConcurrentTransfers.vnd(500));

        assertThat(eventsOf(deposit.deposit().id())).isEmpty();
        assertThat(eventsAboutWallet(wallet)).isZero();
    }

    @Test
    void retriedRequestDoesNotAddASecondEvent() {
        UUID source = api.openWalletWith(1_000);
        UUID target = api.openWallet();
        String key = UUID.randomUUID().toString();

        String transferId =
                (String) body(api.transfer(key, source, target, "300")).get("id");
        api.transfer(key, source, target, "300").expectStatus().isCreated();

        assertThat(eventsOf(UUID.fromString(transferId))).hasSize(1);
        assertThat(eventsAboutWallet(target)).isEqualTo(1);
    }

    @Test
    void everyCompletedTransferOfAConcurrentRunHasItsEventAndNoRejectedOneDoes() throws Exception {
        List<UUID> wallets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wallets.add(support.openWallet(50));
        }
        List<Callable<List<TransferResult>>> tasks = new ArrayList<>();
        for (int thread = 0; thread < 20; thread++) {
            Random random = new Random(thread);
            tasks.add(() -> {
                List<TransferResult> results = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    int from = random.nextInt(wallets.size());
                    int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
                    results.add(support.transfer(wallets.get(from), wallets.get(to), 1 + random.nextInt(40)));
                }
                return results;
            });
        }

        Outcome outcome = ConcurrentTransfers.runTogether(tasks, 60);

        assertThat(outcome.failures()).isEmpty();
        List<UUID> completed = outcome.results().stream()
                .filter(Completed.class::isInstance)
                .map(result -> ((Completed) result).transfer().id())
                .toList();
        assertThat(completed).isNotEmpty().hasSizeLessThan(outcome.results().size());
        assertThat(eventAggregatesAboutWallets(wallets)).containsExactlyInAnyOrderElementsOf(completed);
    }

    @Test
    void appendOutsideATransactionIsRefused() {
        UUID aggregate = UUID.randomUUID();

        assertThatThrownBy(() -> outbox.append(event(aggregate, "OrderPlaced")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(eventsOf(aggregate)).isEmpty();
    }

    @Test
    void anAggregateHasAtMostOneEventOfEachType() {
        UUID aggregate = UUID.randomUUID();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    outbox.append(event(aggregate, "OrderPlaced"));
                    outbox.append(event(aggregate, "OrderPlaced"));
                }))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(eventsOf(aggregate)).isEmpty();

        transaction.executeWithoutResult(status -> {
            outbox.append(event(aggregate, "OrderPlaced"));
            outbox.append(event(aggregate, "OrderShipped"));
        });
        assertThat(eventsOf(aggregate)).hasSize(2);
    }

    private static OutboxEvent event(UUID aggregate, String type) {
        return new OutboxEvent("test.topic", "Order", aggregate, type, 1, Map.of("total", "15"));
    }

    private List<Map<String, @Nullable Object>> eventsOf(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT topic, aggregate_type, event_type, event_version, published_at, attempts,
                            payload ->> 'sourceWalletId' AS source, payload ->> 'targetWalletId' AS target,
                            payload ->> 'amount' AS amount, payload ->> 'currency' AS currency
                        FROM outbox_events
                        WHERE aggregate_id = :id
                        """).param("id", aggregateId).query().listOfRows();
    }

    private long eventsAboutWallet(UUID wallet) {
        return eventAggregatesAboutWallets(List.of(wallet)).size();
    }

    private List<UUID> eventAggregatesAboutWallets(List<UUID> wallets) {
        return jdbc.sql("""
                        SELECT aggregate_id FROM outbox_events
                        WHERE payload ->> 'sourceWalletId' = ANY (:wallets)
                           OR payload ->> 'targetWalletId' = ANY (:wallets)
                        """)
                .param("wallets", wallets.stream().map(UUID::toString).toArray(String[]::new))
                .query((rs, row) -> rs.getObject("aggregate_id", UUID.class))
                .list();
    }
}
