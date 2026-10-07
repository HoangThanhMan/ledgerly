package dev.ledgerly.outbox.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository;
import dev.ledgerly.shared.Money;
import dev.ledgerly.wallet.internal.application.OpenWalletResult;
import dev.ledgerly.wallet.internal.application.TransferResult;
import dev.ledgerly.wallet.internal.application.TransferService;
import dev.ledgerly.wallet.internal.application.WalletService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invariant I6, second half: every event in the outbox reaches Kafka. The scheduler is off in tests, so each test
 * runs the relay itself and knows exactly how many rounds happened.
 */
// A relay that never gets through its backlog would run forever. Fail instead.
@Timeout(120)
class OutboxRelayIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayIT.class);

    private static final Currency VND = Currency.getInstance("VND");
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** What the outbox row of an event says about it. */
    record Written(UUID id, Instant createdAt) {}

    private final OutboxRelay relay;
    private final OutboxEventRepository events;
    private final OutboxWriter outbox;
    private final WalletService wallets;
    private final TransferService transfers;
    private final ConsumerFactory<String, String> consumers;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final ObjectProvider<OutboxRelayScheduler> scheduler;

    OutboxRelayIT(
            @Autowired OutboxRelay relay,
            @Autowired OutboxEventRepository events,
            @Autowired OutboxWriter outbox,
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired ConsumerFactory<String, String> consumers,
            @Autowired JdbcClient jdbc,
            @Autowired TransactionTemplate transaction,
            @Autowired JsonMapper json,
            @Autowired MeterRegistry meters,
            @Autowired ObjectProvider<OutboxRelayScheduler> scheduler) {
        this.relay = relay;
        this.events = events;
        this.outbox = outbox;
        this.wallets = wallets;
        this.transfers = transfers;
        this.consumers = consumers;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.meters = meters;
        this.scheduler = scheduler;
    }

    /** Other tests leave thousands of unpublished events behind. Each test here starts from an empty backlog. */
    @BeforeEach
    void emptyTheBacklog() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL")
                .update();
    }

    @Test
    void thousandTransfersBecomeThousandMessagesAndTheBacklogEmpties() {
        int count = 1_000;
        UUID source = openWallet(count);
        UUID target = openWallet(0);
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            Set<String> transferIds = new HashSet<>();
            for (int i = 0; i < count; i++) {
                transferIds.add(transfer(source, target, 1).toString());
            }
            assertThat(pendingGauge()).isEqualTo(count);
            assertThat(oldestAgeGauge()).isPositive();

            long started = System.nanoTime();
            int published = relay.drain();
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            log.info("Relayed {} events in {} ms", published, took.toMillis());

            assertThat(published).isEqualTo(count);
            List<ConsumerRecord<String, String>> records = reader.read(transferIds, count, PATIENCE);
            assertThat(records).hasSize(count);
            assertThat(records.stream().map(ConsumerRecord::key).collect(Collectors.toSet()))
                    .isEqualTo(transferIds);
            assertThat(records.stream()
                            .map(record -> envelope(record).get("eventId"))
                            .collect(Collectors.toSet()))
                    .hasSize(count);
        }
        assertThat(events.countPending()).isZero();
        assertThat(pendingGauge()).isZero();
        assertThat(oldestAgeGauge()).isZero();
    }

    @Test
    void publishedEventHasTheShapeOfTheContractSample() throws IOException {
        UUID source = openWallet(1_000);
        UUID target = openWallet(0);
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            UUID transferId = transfer(source, target, 300);
            Written written = jdbc.sql("SELECT id, created_at FROM outbox_events WHERE aggregate_id = :id")
                    .param("id", transferId)
                    .query((rs, row) -> new Written(
                            rs.getObject("id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                    .single();

            relay.drain();

            ConsumerRecord<String, String> record =
                    reader.read(Set.of(transferId.toString()), 1, PATIENCE).getFirst();
            Map<String, Object> published = envelope(record);
            Map<String, Object> sample = sample("/contracts/transfer-completed.v1.json");
            assertThat(published).containsOnlyKeys(sample.keySet());
            assertThat(payloadOf(published)).containsOnlyKeys(payloadOf(sample).keySet());
            assertThat(published)
                    .containsEntry("eventId", written.id().toString())
                    .containsEntry("eventType", "TransferCompleted")
                    .containsEntry("eventVersion", 1)
                    .containsEntry("aggregateType", "LedgerTransaction")
                    .containsEntry("aggregateId", transferId.toString());
            assertThat(Instant.parse((String) published.get("occurredAt"))).isEqualTo(written.createdAt());
            assertThat(payloadOf(published))
                    .containsEntry("sourceWalletId", source.toString())
                    .containsEntry("targetWalletId", target.toString())
                    .containsEntry("amount", "300")
                    .containsEntry("currency", "VND");
        }
    }

    @Test
    void eventsOfOneAggregateStayInOrderOnOnePartition() {
        UUID aggregate = UUID.randomUUID();
        List<String> types = List.of("Opened", "Changed", "Closed");
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            for (String type : types) {
                transaction.executeWithoutResult(status -> outbox.append(
                        new OutboxEvent(Topics.TRANSFERS, "Order", aggregate, type, 1, Map.of("step", type))));
            }

            relay.drain();

            List<ConsumerRecord<String, String>> records = reader.read(Set.of(aggregate.toString()), 3, PATIENCE);
            assertThat(records)
                    .extracting(record -> envelope(record).get("eventType"))
                    .containsExactlyElementsOf(types);
            assertThat(records)
                    .extracting(ConsumerRecord::partition)
                    .containsOnly(records.getFirst().partition());
            assertThat(records).extracting(ConsumerRecord::offset).isSorted();
        }
    }

    @Test
    void oneRoundPublishesAtMostOneBatch() {
        for (int i = 0; i < 250; i++) {
            events.insert("test.batches", "Order", UUID.randomUUID(), "OrderPlaced", 1, "{}");
        }

        assertThat(relay.relayBatch()).isEqualTo(100);
        assertThat(events.countPending()).isEqualTo(150);
        assertThat(relay.relayBatch()).isEqualTo(100);
        assertThat(relay.relayBatch()).isEqualTo(50);
        assertThat(relay.relayBatch()).isZero();
        assertThat(events.countPending()).isZero();
    }

    @Test
    void whileTheBrokerIsUnreachableEventsStayInTheOutboxAndNoneIsLost() {
        UUID source = openWallet(10);
        UUID target = openWallet(0);
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            // The transfers themselves never touch Kafka: they succeed whatever state the broker is in.
            Set<String> transferIds = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                transferIds.add(transfer(source, target, 1).toString());
            }

            OutboxRelay cutOff = relayWithUnreachableBroker();
            assertThatThrownBy(cutOff::relayBatch).isInstanceOf(EventPublishException.class);
            assertThatThrownBy(cutOff::relayBatch).isInstanceOf(EventPublishException.class);

            assertThat(events.countPending()).isEqualTo(3);
            // Only the first event of the batch was tried each time: the relay stops at the first failure.
            assertThat(attemptsByEvent(transferIds).values()).containsExactly(2, 0, 0);

            assertThat(relay.drain()).isEqualTo(3);

            assertThat(reader.read(transferIds, 3, PATIENCE)).hasSize(3);
            assertThat(events.countPending()).isZero();
        }
    }

    @Test
    void schedulerIsOffInThisContext() {
        assertThat(scheduler.getIfAvailable()).isNull();
    }

    private OutboxRelay relayWithUnreachableBroker() {
        Map<String, Object> producer = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                300);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producer));
        return new OutboxRelay(
                events,
                new KafkaEventPublisher(template, json, Duration.ofMillis(500)),
                transaction,
                new StaticListableBeanFactory().getBeanProvider(OutboxFaults.class),
                100);
    }

    /** Failed attempts of the events of the given transfers, in the order the events were written. */
    private Map<UUID, Integer> attemptsByEvent(Set<String> transferIds) {
        Map<UUID, Integer> attempts = new LinkedHashMap<>();
        jdbc.sql("SELECT id, attempts FROM outbox_events WHERE aggregate_id = ANY (:ids) ORDER BY created_at, id")
                .param("ids", transferIds.stream().map(UUID::fromString).toArray(UUID[]::new))
                .query((rs, row) -> attempts.put(rs.getObject("id", UUID.class), rs.getInt("attempts")))
                .list();
        return attempts;
    }

    private UUID openWallet(long balance) {
        UUID wallet = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        if (balance > 0) {
            transfers.deposit(wallet, Money.of(balance, VND));
        }
        return wallet;
    }

    private UUID transfer(UUID source, UUID target, long amount) {
        TransferResult result = transfers.transfer(source, target, Money.of(amount, VND));
        return ((TransferResult.Completed) result).transfer().id();
    }

    private double pendingGauge() {
        return meters.get("ledgerly.outbox.pending").gauge().value();
    }

    private double oldestAgeGauge() {
        return meters.get("ledgerly.outbox.oldest.age").timeGauge().value(TimeUnit.MILLISECONDS);
    }

    private Map<String, Object> envelope(ConsumerRecord<String, String> record) {
        return json.readValue(record.value(), JSON_OBJECT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> envelope) {
        return (Map<String, Object>) Objects.requireNonNull(envelope.get("payload"));
    }

    private Map<String, Object> sample(String resource) throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(resource), resource)) {
            return Objects.requireNonNull(json.readValue(in, JSON_OBJECT));
        }
    }
}
