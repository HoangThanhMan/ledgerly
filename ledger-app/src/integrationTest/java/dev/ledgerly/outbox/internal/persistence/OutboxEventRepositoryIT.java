package dev.ledgerly.outbox.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.outbox.internal.persistence.OutboxEventRepository.OutboxRow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The SQL statements of the outbox, one at a time. */
class OutboxEventRepositoryIT extends AbstractIntegrationTest {

    private static final String TOPIC = "test.topic";

    private final OutboxEventRepository events;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    OutboxEventRepositoryIT(
            @Autowired OutboxEventRepository events,
            @Autowired JdbcClient jdbc,
            @Autowired TransactionTemplate transaction) {
        this.events = events;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** The statements under test look at every unpublished event, so each test starts from an empty backlog. */
    @BeforeEach
    void emptyTheBacklog() {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL")
                .update();
    }

    @Test
    void insertedEventIsUnpublishedAndReadBackAsWritten() {
        UUID aggregate = UUID.randomUUID();

        UUID id = events.insert(TOPIC, "Order", aggregate, "OrderPlaced", 2, "{\"total\":\"15\"}");

        assertThat(inTransaction(() -> events.lockNextBatch(10)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.id()).isEqualTo(id);
                    assertThat(row.topic()).isEqualTo(TOPIC);
                    assertThat(row.aggregateType()).isEqualTo("Order");
                    assertThat(row.aggregateId()).isEqualTo(aggregate);
                    assertThat(row.eventType()).isEqualTo("OrderPlaced");
                    assertThat(row.eventVersion()).isEqualTo(2);
                    assertThat(row.payload()).isEqualToIgnoringWhitespace("{\"total\":\"15\"}");
                    assertThat(row.createdAt()).isNotNull();
                });
        assertThat(attemptsOf(id)).isZero();
    }

    @Test
    void batchIsTheOldestUnpublishedEventsInTheOrderTheyWereWritten() {
        UUID first = insert();
        UUID second = insert();
        UUID third = insert();

        assertThat(idsOf(inTransaction(() -> events.lockNextBatch(2)))).containsExactly(first, second);
        assertThat(idsOf(inTransaction(() -> events.lockNextBatch(10)))).containsExactly(first, second, third);
    }

    @Test
    void eventsWrittenEarlierComeFirstEvenIfTheirIdsSortLater() {
        UUID newer = insert();
        UUID older = insert();
        jdbc.sql("UPDATE outbox_events SET created_at = created_at - interval '1 minute' WHERE id = :id")
                .param("id", older)
                .update();

        assertThat(idsOf(inTransaction(() -> events.lockNextBatch(10)))).containsExactly(older, newer);
    }

    @Test
    void publishedEventsAreNotPickedUpAgain() {
        UUID published = insert();
        UUID pending = insert();

        events.markPublished(List.of(published));

        assertThat(idsOf(inTransaction(() -> events.lockNextBatch(10)))).containsExactly(pending);
        assertThat(publishedAtIsSet(published)).isTrue();
        assertThat(publishedAtIsSet(pending)).isFalse();
    }

    @Test
    void secondRelaySkipsTheEventsTheFirstHasLockedInsteadOfWaitingForThem() {
        UUID first = insert();
        UUID second = insert();
        UUID third = insert();

        transaction.executeWithoutResult(status -> {
            assertThat(idsOf(events.lockNextBatch(2))).containsExactly(first, second);

            // Another relay, on its own connection, while this transaction still holds the two rows.
            List<UUID> seenByOther = CompletableFuture.supplyAsync(
                            () -> idsOf(inTransaction(() -> events.lockNextBatch(10))))
                    .join();

            assertThat(seenByOther).containsExactly(third);
        });
    }

    @Test
    void failedAttemptIsCountedOnEventsThatAreStillUnpublished() {
        UUID published = insert();
        UUID pending = insert();
        events.markPublished(List.of(published));

        events.recordFailedAttempt(List.of(published, pending));
        events.recordFailedAttempt(List.of(pending));

        assertThat(attemptsOf(pending)).isEqualTo(2);
        assertThat(attemptsOf(published)).isZero();
    }

    @Test
    void backlogSizeAndAgeAreZeroWhenEverythingIsPublished() {
        assertThat(events.countPending()).isZero();
        assertThat(events.oldestPendingAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void backlogAgeIsTheAgeOfTheOldestUnpublishedEvent() {
        insert();
        UUID old = insert();
        jdbc.sql("UPDATE outbox_events SET created_at = now() - interval '90 seconds' WHERE id = :id")
                .param("id", old)
                .update();

        assertThat(events.countPending()).isEqualTo(2);
        assertThat(events.oldestPendingAge()).isBetween(Duration.ofSeconds(90), Duration.ofSeconds(100));
    }

    @Test
    void unpublishedEventsHaveAnIndexOfTheirOwn() {
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'outbox_events_unpublished'")
                .query(String.class)
                .single();

        assertThat(definition).contains("(created_at, id)").contains("WHERE (published_at IS NULL)");
    }

    private UUID insert() {
        return events.insert(TOPIC, "Order", UUID.randomUUID(), "OrderPlaced", 1, "{}");
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return java.util.Objects.requireNonNull(transaction.execute(status -> work.get()));
    }

    private static List<UUID> idsOf(List<OutboxRow> rows) {
        return rows.stream().map(OutboxRow::id).toList();
    }

    private int attemptsOf(UUID id) {
        return jdbc.sql("SELECT attempts FROM outbox_events WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private boolean publishedAtIsSet(UUID id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }
}
