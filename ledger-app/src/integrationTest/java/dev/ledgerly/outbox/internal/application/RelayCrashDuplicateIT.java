package dev.ledgerly.outbox.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.OpenWalletResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import java.time.Duration;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * The relay dies after Kafka has taken a batch and before the database knows about it. The next round publishes
 * the batch again: nothing is lost, and the price is duplicates, which consumers have to drop.
 */
class RelayCrashDuplicateIT extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** Thrown where the relay is supposed to have died. */
    static final class SimulatedCrash extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("relay crashed after sending the batch");
        }
    }

    /** Fails the next rounds right after their events were acknowledged by Kafka. */
    static final class CrashAfterSend implements OutboxFaults {

        private final AtomicInteger roundsToCrash = new AtomicInteger();

        void crashNextRounds(int rounds) {
            roundsToCrash.set(rounds);
        }

        @Override
        public void afterSend(List<UUID> eventIds) {
            if (roundsToCrash.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new SimulatedCrash();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {

        @Bean
        CrashAfterSend crashAfterSend() {
            return new CrashAfterSend();
        }
    }

    private final OutboxRelay relay;
    private final CrashAfterSend faults;
    private final OutboxEventRepository events;
    private final WalletService wallets;
    private final TransferService transfers;
    private final ConsumerFactory<String, String> consumers;
    private final JdbcClient jdbc;

    RelayCrashDuplicateIT(
            @Autowired OutboxRelay relay,
            @Autowired CrashAfterSend faults,
            @Autowired OutboxEventRepository events,
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired ConsumerFactory<String, String> consumers,
            @Autowired JdbcClient jdbc) {
        this.relay = relay;
        this.faults = faults;
        this.events = events;
        this.wallets = wallets;
        this.transfers = transfers;
        this.consumers = consumers;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void emptyTheBacklog() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL")
                .update();
    }

    @Test
    void batchSentBeforeACrashIsSentAgainSoNothingIsLostAndCopiesAppear() {
        int transfersMade = 5;
        int crashes = 2;
        UUID source = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        UUID target = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        transfers.deposit(source, Money.of(100, VND));
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            Set<String> transferIds = new HashSet<>();
            for (int i = 0; i < transfersMade; i++) {
                TransferResult result = transfers.transfer(source, target, Money.of(1, VND));
                transferIds.add(
                        ((TransferResult.Completed) result).transfer().id().toString());
            }
            faults.crashNextRounds(crashes);

            assertThatThrownBy(relay::relayBatch).isInstanceOf(SimulatedCrash.class);
            assertThatThrownBy(relay::relayBatch).isInstanceOf(SimulatedCrash.class);
            // Kafka has the events twice by now, the database still has every one of them as unpublished.
            assertThat(events.countPending()).isEqualTo(transfersMade);

            assertThat(relay.relayBatch()).isEqualTo(transfersMade);

            int copies = crashes + 1;
            List<ConsumerRecord<String, String>> records = reader.read(transferIds, transfersMade * copies, PATIENCE);
            Map<String, List<String>> valuesByTransfer = records.stream()
                    .collect(Collectors.groupingBy(
                            ConsumerRecord::key, Collectors.mapping(ConsumerRecord::value, Collectors.toList())));
            assertThat(valuesByTransfer.keySet()).isEqualTo(transferIds);
            assertThat(valuesByTransfer.values()).allSatisfy(values -> {
                assertThat(values).hasSize(copies);
                // Every copy is the same bytes, eventId included: that is what lets a consumer recognize it.
                assertThat(new HashSet<>(values)).hasSize(1);
            });
            assertThat(records).hasSize(transfersMade * copies);
        }
        assertThat(events.countPending()).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM outbox_events WHERE payload ->> 'targetWalletId' = :target")
                        .param("target", target.toString())
                        .query(Integer.class)
                        .list())
                .hasSize(transfersMade)
                .containsOnly(crashes);
    }
}
