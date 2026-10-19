package dev.ledgerly.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.contracts.TransferCompleted;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invariant I7: an event has its effect once, however many times Kafka delivers it. The messages are produced the
 * way the relay produces them, copies included.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConsumerDedupIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MeterRegistry meters;
    private final NotificationService notifications;
    private final TransactionTemplate transaction;

    ConsumerDedupIT(
            @Autowired KafkaTemplate<String, String> kafka,
            @Autowired JdbcClient jdbc,
            @Autowired JsonMapper json,
            @Autowired MeterRegistry meters,
            @Autowired NotificationService notifications,
            @Autowired TransactionTemplate transaction) {
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.json = json;
        this.meters = meters;
        this.notifications = notifications;
        this.transaction = transaction;
    }

    @Test
    void sameEventDeliveredFiveTimesCreatesOneNotificationAndFourAreCountedAsDuplicates() {
        EventEnvelope<TransferCompleted> event = transferTo(UUID.randomUUID(), "150000");
        double duplicatesBefore = duplicates();

        for (int i = 0; i < 5; i++) {
            send(event);
        }

        // All five have been consumed once the four copies are counted.
        await().atMost(PATIENCE).until(() -> duplicates() - duplicatesBefore == 4);
        assertThat(messagesFor(event.payload().targetWalletId()))
                .containsExactly(
                        "You received 150000 VND from wallet " + event.payload().sourceWalletId());
        assertThat(processedType(event.eventId())).containsExactly("TransferCompleted");
    }

    @Test
    void differentEventsEachCreateTheirOwnNotification() {
        UUID wallet = UUID.randomUUID();
        double duplicatesBefore = duplicates();

        send(transferTo(wallet, "1"));
        send(transferTo(wallet, "2"));
        send(transferTo(wallet, "3"));

        await().atMost(PATIENCE).until(() -> messagesFor(wallet).size() == 3);
        assertThat(messagesFor(wallet))
                .extracting(message -> message.substring("You received ".length(), message.indexOf(" VND")))
                .containsExactlyInAnyOrder("1", "2", "3");
        assertThat(duplicates()).isEqualTo(duplicatesBefore);
    }

    @Test
    void messageThatCannotBeReadIsSkippedAndDoesNotHoldUpTheOnesBehindIt() {
        EventEnvelope<TransferCompleted> event = transferTo(UUID.randomUUID(), "70");
        // One key, so all three land on one partition, in this order.
        String key = event.aggregateId().toString();

        publish(key, "not json");
        publish(key, "{\"eventType\":\"TransferCompleted\"}");
        publish(key, json.writeValueAsString(event));

        await().atMost(PATIENCE)
                .until(() -> messagesFor(event.payload().targetWalletId()).size() == 1);
    }

    @Test
    void eventsOfOtherTypesOnTheTopicAreLeftAlone() {
        UUID wallet = UUID.randomUUID();
        EventEnvelope<TransferCompleted> transfer = transferTo(wallet, "5");
        EventEnvelope<TransferCompleted> other = new EventEnvelope<>(
                UUID.randomUUID(),
                "TransferReversed",
                1,
                Instant.now(),
                TransferCompleted.AGGREGATE_TYPE,
                transfer.aggregateId(),
                transfer.payload());

        send(other);
        send(transfer);

        await().atMost(PATIENCE).until(() -> messagesFor(wallet).size() == 1);
        assertThat(processedType(other.eventId())).isEmpty();
    }

    @Test
    void eventWhoseHandlingRolledBackIsNotRememberedAsProcessed() {
        EventEnvelope<TransferCompleted> event = transferTo(UUID.randomUUID(), "40");
        double duplicatesBefore = duplicates();

        transaction.executeWithoutResult(status -> {
            notifications.handle(event);
            // The database fails before the commit. Kafka will deliver the event again.
            status.setRollbackOnly();
        });
        assertThat(processedType(event.eventId())).isEmpty();
        assertThat(messagesFor(event.payload().targetWalletId())).isEmpty();

        notifications.handle(event);

        assertThat(messagesFor(event.payload().targetWalletId())).hasSize(1);
        assertThat(duplicates()).isEqualTo(duplicatesBefore);
    }

    private void send(EventEnvelope<TransferCompleted> event) {
        publish(event.aggregateId().toString(), json.writeValueAsString(event));
    }

    /** Returns once the broker has the record, so the records of a test are on the topic in the order sent. */
    private void publish(String key, String value) {
        kafka.send(Topics.TRANSFERS, key, value).join();
    }

    private static EventEnvelope<TransferCompleted> transferTo(UUID targetWallet, String amount) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                TransferCompleted.EVENT_TYPE,
                TransferCompleted.VERSION,
                Instant.now(),
                TransferCompleted.AGGREGATE_TYPE,
                UUID.randomUUID(),
                new TransferCompleted(UUID.randomUUID(), targetWallet, amount, "VND"));
    }

    private List<String> messagesFor(UUID wallet) {
        return jdbc.sql("SELECT message FROM notifications WHERE account_id = :wallet ORDER BY id")
                .param("wallet", wallet)
                .query((rs, row) -> rs.getString("message"))
                .list();
    }

    private List<String> processedType(UUID eventId) {
        return jdbc.sql("SELECT event_type FROM processed_events WHERE event_id = :id")
                .param("id", eventId)
                .query((rs, row) -> rs.getString("event_type"))
                .list();
    }

    private double duplicates() {
        return meters.counter("notification.duplicates").count();
    }
}
