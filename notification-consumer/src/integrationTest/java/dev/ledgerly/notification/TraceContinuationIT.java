package dev.ledgerly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.contracts.TransferCompleted;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The consumer continues the trace that arrives in the record's {@code traceparent} header. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "management.tracing.sampling.probability=1.0")
class TraceContinuationIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** Collects the spans the application would export, so the test can look at them. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCollector {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final InMemorySpanExporter spans;

    TraceContinuationIT(
            @Autowired KafkaTemplate<String, String> kafka,
            @Autowired JdbcClient jdbc,
            @Autowired JsonMapper json,
            @Autowired InMemorySpanExporter spans) {
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.json = json;
        this.spans = spans;
    }

    @Test
    void handlingAnEventIsPartOfTheTraceThatProducedIt() {
        UUID wallet = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        EventEnvelope<TransferCompleted> event = new EventEnvelope<>(
                UUID.randomUUID(),
                TransferCompleted.EVENT_TYPE,
                TransferCompleted.VERSION,
                Instant.now(),
                TransferCompleted.AGGREGATE_TYPE,
                UUID.randomUUID(),
                new TransferCompleted(UUID.randomUUID(), wallet, "25", "VND"));
        ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.TRANSFERS, event.aggregateId().toString(), json.writeValueAsString(event));
        // What the relay of ledger-app puts on the record: the trace of the transfer.
        record.headers()
                .add("traceparent", ("00-" + traceId + "-00f067aa0ba902b7-01").getBytes(StandardCharsets.UTF_8));

        kafka.send(record).join();

        await().atMost(PATIENCE).until(() -> notificationsFor(wallet) == 1);
        await().atMost(PATIENCE).untilAsserted(() -> {
            List<SpanData> trace = spans.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .toList();
            assertThat(trace).extracting(SpanData::getKind).contains(SpanKind.CONSUMER);
            // The two statements of the handler: remember the event, write the notification.
            assertThat(trace).filteredOn(span -> span.getName().equals("query")).hasSizeGreaterThanOrEqualTo(2);
        });
    }

    private long notificationsFor(UUID wallet) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE account_id = :wallet")
                .param("wallet", wallet)
                .query(Long.class)
                .single();
    }
}
