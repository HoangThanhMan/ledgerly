package dev.ledgerly.outbox.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.OpenWalletResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * The relay as it runs in production: on a schedule, with nobody calling it.
 *
 * <p>The context of this class stays cached like any other, so its relay is cancelled when the class is done.
 * Otherwise it would go on publishing the events of the tests that follow. Closing the context instead is not an
 * option, see {@link AbstractIntegrationTest}.
 */
@TestPropertySource(properties = {"ledgerly.outbox.relay.enabled=true", "ledgerly.outbox.relay.poll-interval=50ms"})
@TestInstance(Lifecycle.PER_CLASS)
class OutboxRelaySchedulerIT extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final WalletService wallets;
    private final TransferService transfers;
    private final ConsumerFactory<String, String> consumers;
    private final JdbcClient jdbc;
    private final ScheduledTaskHolder scheduledTasks;

    OutboxRelaySchedulerIT(
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired ConsumerFactory<String, String> consumers,
            @Autowired JdbcClient jdbc,
            @Autowired ScheduledTaskHolder scheduledTasks) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.consumers = consumers;
        this.jdbc = jdbc;
        this.scheduledTasks = scheduledTasks;
    }

    @AfterAll
    void cancelTheRelay() {
        // Not interrupting: a round that is under way finishes and commits.
        relayTasks().forEach(task -> task.cancel(false));
    }

    @BeforeEach
    void emptyTheBacklog() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL")
                .update();
    }

    @Test
    void committedTransferReachesKafkaWithoutAnyoneCallingTheRelay() {
        UUID source = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        UUID target = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        transfers.deposit(source, Money.of(100, VND));
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            TransferResult result = transfers.transfer(source, target, Money.of(10, VND));
            UUID transferId = ((TransferResult.Completed) result).transfer().id();

            await().atMost(PATIENCE).until(() -> isPublished(transferId));

            assertThat(reader.read(Set.of(transferId.toString()), 1, PATIENCE)).hasSize(1);
        }
    }

    @Test
    void relayIsScheduledWithTheConfiguredDelay() {
        assertThat(relayTasks())
                .singleElement()
                .satisfies(task -> assertThat(task.getTask())
                        .isInstanceOfSatisfying(
                                FixedDelayTask.class,
                                fixedDelay -> assertThat(fixedDelay.getIntervalDuration())
                                        .isEqualTo(Duration.ofMillis(50))));
    }

    private List<ScheduledTask> relayTasks() {
        return scheduledTasks.getScheduledTasks().stream()
                .filter(task -> task.toString().contains("OutboxRelayScheduler.poll"))
                .toList();
    }

    private boolean isPublished(UUID transferId) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = :id")
                .param("id", transferId)
                .query(Boolean.class)
                .single();
    }
}
