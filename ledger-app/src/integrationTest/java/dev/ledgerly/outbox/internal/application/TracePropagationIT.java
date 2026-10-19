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
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * One trace follows a transfer from the HTTP request to the Kafka record, although the relay publishes the event
 * later and on another thread: the trace context travels in the outbox row.
 */
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
@Timeout(120)
class TracePropagationIT extends AbstractIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** Collects the spans the application would export, so the test can look at them. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCollector {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    private final RestTestClient client;
    private final OutboxRelay relay;
    private final WalletService wallets;
    private final TransferService transfers;
    private final ConsumerFactory<String, String> consumers;
    private final JdbcClient jdbc;
    private final InMemorySpanExporter spans;

    TracePropagationIT(
            @Autowired RestTestClient client,
            @Autowired OutboxRelay relay,
            @Autowired WalletService wallets,
            @Autowired TransferService transfers,
            @Autowired ConsumerFactory<String, String> consumers,
            @Autowired JdbcClient jdbc,
            @Autowired InMemorySpanExporter spans) {
        this.client = client;
        this.relay = relay;
        this.wallets = wallets;
        this.transfers = transfers;
        this.consumers = consumers;
        this.jdbc = jdbc;
        this.spans = spans;
    }

    @BeforeEach
    void emptyTheBacklog() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL")
                .update();
    }

    @Test
    void traceOfTheRequestContinuesThroughTheOutboxIntoTheKafkaRecord() {
        UUID source = openWallet(1_000);
        UUID target = openWallet(0);
        String traceId = hex(32);
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            client.post()
                    .uri("/v1/transfers")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    // A caller that is traced already: the server joins its trace.
                    .header("traceparent", "00-" + traceId + "-" + hex(16) + "-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "sourceWalletId", source, "targetWalletId", target, "amount", "300", "currency", "VND"))
                    .exchange()
                    .expectStatus()
                    .isCreated();

            assertThat(storedTraceparent(target)).startsWith("00-" + traceId + "-");

            relay.drain();

            ConsumerRecord<String, String> record =
                    reader.readFirstWithPayloadField("targetWalletId", target.toString(), PATIENCE);
            Header traceparent = record.headers().lastHeader("traceparent");
            assertThat(traceparent).isNotNull();
            assertThat(new String(traceparent.value(), StandardCharsets.UTF_8)).startsWith("00-" + traceId + "-");
        }

        await().atMost(PATIENCE).untilAsserted(() -> {
            List<SpanData> trace = spansOf(traceId);
            assertThat(trace).extracting(SpanData::getKind).contains(SpanKind.SERVER, SpanKind.PRODUCER);
            assertThat(trace).extracting(SpanData::getName).contains("outbox publish", "query");
        });
        // The relay's span hangs under the request that wrote the event, not under a trace of its own.
        SpanData publish = spansOf(traceId).stream()
                .filter(span -> span.getName().equals("outbox publish"))
                .findFirst()
                .orElseThrow();
        assertThat(spansOf(traceId)).extracting(SpanData::getSpanId).contains(publish.getParentSpanId());
    }

    @Test
    void eventWrittenOutsideAnyTraceCarriesNoTraceContextAndIsStillPublished() {
        UUID source = openWallet(1_000);
        UUID target = openWallet(0);
        try (TopicReader reader = new TopicReader(consumers, Topics.TRANSFERS)) {
            UUID transferId = ((TransferResult.Completed) transfers.transfer(source, target, Money.of(5, VND)))
                    .transfer()
                    .id();

            assertThat(storedTraceparent(target)).isNull();

            relay.drain();

            assertThat(reader.read(Set.of(transferId.toString()), 1, PATIENCE)).hasSize(1);
        }
    }

    private List<SpanData> spansOf(String traceId) {
        return spans.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }

    private @org.jspecify.annotations.Nullable String storedTraceparent(UUID targetWallet) {
        return jdbc.sql(
                        "SELECT headers ->> 'traceparent' FROM outbox_events WHERE payload ->> 'targetWalletId' = :target")
                .param("target", targetWallet.toString())
                .query((rs, row) -> java.util.Optional.ofNullable(rs.getString(1)))
                .single()
                .orElse(null);
    }

    private UUID openWallet(long balance) {
        UUID wallet = ((OpenWalletResult.Opened) wallets.open(VND)).wallet().id();
        if (balance > 0) {
            transfers.deposit(wallet, Money.of(balance, VND));
        }
        return wallet;
    }

    private static String hex(int length) {
        String digits = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        return digits.substring(0, length);
    }
}
