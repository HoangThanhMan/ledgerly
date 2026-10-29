package dev.ledgerly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.TicketPurchased;
import dev.ledgerly.contracts.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A buyer is told about their tickets once, however many times Kafka delivers the purchase. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TicketNotificationIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry meters;

    TicketNotificationIT(
            @Autowired KafkaTemplate<String, String> kafka,
            @Autowired JdbcClient jdbc,
            @Autowired JsonMapper json,
            @Autowired MeterRegistry meters) {
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
    }

    @Test
    void samePurchaseDeliveredThreeTimesNotifiesTheBuyerOnce() {
        EventEnvelope<TicketPurchased> event = purchase(UUID.randomUUID());
        double duplicatesBefore = duplicates();

        for (int i = 0; i < 3; i++) {
            send(event);
        }

        await().atMost(PATIENCE).until(() -> duplicates() - duplicatesBefore == 2);
        assertThat(messagesFor(event.payload().buyerWalletId()))
                .containsExactly("You bought 2 tickets of class VIP for Ledgerly Live");
    }

    @Test
    void purchaseWithoutAnOrderIdIsSkippedAndDoesNotHoldUpTheOnesBehindIt() {
        UUID wallet = UUID.randomUUID();
        EventEnvelope<TicketPurchased> event = purchase(wallet);
        String key = event.aggregateId().toString();
        EventEnvelope<TicketPurchased> incomplete = purchase(wallet);
        String withoutOrderId = json.writeValueAsString(incomplete).replaceFirst("\"orderId\":\"[^\"]+\",", "");

        assertThat(withoutOrderId).doesNotContain("orderId");
        publish(key, withoutOrderId);
        publish(key, json.writeValueAsString(event));

        // One key, so the incomplete event was consumed before the complete one.
        await().atMost(PATIENCE).until(() -> processed(event.eventId()));
        assertThat(processed(incomplete.eventId())).isFalse();
        assertThat(messagesFor(wallet)).hasSize(1);
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private void send(EventEnvelope<TicketPurchased> event) {
        publish(event.aggregateId().toString(), json.writeValueAsString(event));
    }

    /** Returns once the broker has the record, so the records of a test are on the topic in the order sent. */
    private void publish(String key, String value) {
        kafka.send(Topics.TICKETS, key, value).join();
    }

    private static EventEnvelope<TicketPurchased> purchase(UUID buyerWallet) {
        UUID orderId = UUID.randomUUID();
        return new EventEnvelope<>(
                UUID.randomUUID(),
                TicketPurchased.EVENT_TYPE,
                TicketPurchased.VERSION,
                Instant.now(),
                TicketPurchased.AGGREGATE_TYPE,
                orderId,
                new TicketPurchased(
                        orderId,
                        UUID.randomUUID(),
                        "Ledgerly Live",
                        UUID.randomUUID(),
                        "VIP",
                        2,
                        "1000000",
                        "VND",
                        UUID.randomUUID(),
                        buyerWallet));
    }

    private List<String> messagesFor(UUID wallet) {
        return jdbc.sql("SELECT message FROM notifications WHERE account_id = :wallet ORDER BY id")
                .param("wallet", wallet)
                .query((rs, row) -> rs.getString("message"))
                .list();
    }

    private double duplicates() {
        return meters.counter("notification.duplicates").count();
    }
}
