package dev.ledgerly.outbox.internal.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxEventRepository {

    /** An event waiting to be published. The payload is JSON text. */
    public record OutboxRow(
            UUID id,
            String topic,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String payload,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    OutboxEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes one unpublished event and returns its id. */
    public UUID insert(
            String topic, String aggregateType, UUID aggregateId, String eventType, int eventVersion, String payload) {
        return jdbc.sql("""
                        INSERT INTO outbox_events (topic, aggregate_type, aggregate_id, event_type, event_version, payload)
                        VALUES (:topic, :aggregateType, :aggregateId, :eventType, :eventVersion, CAST(:payload AS jsonb))
                        RETURNING id
                        """)
                .param("topic", topic)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("eventVersion", eventVersion)
                .param("payload", payload)
                .query(UUID.class)
                .single();
    }

    /**
     * Locks up to {@code limit} unpublished events for the current transaction, oldest first, and returns them.
     *
     * <p>Events another transaction has locked are skipped, not waited for. Several relays can therefore run side
     * by side: each takes a different batch, and none publishes what another is publishing right now.
     *
     * <p>{@code created_at} is the time the event was written, not the time its transaction committed. An event can
     * therefore become visible after younger ones were published. That is why the backlog is found by
     * {@code published_at IS NULL} and not by remembering how far a previous poll got.
     */
    public List<OutboxRow> lockNextBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, topic, aggregate_type, aggregate_id, event_type, event_version, payload, created_at
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query(OutboxEventRepository::toRow)
                .list();
    }

    public void markPublished(Collection<UUID> ids) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = ANY (:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .update();
    }

    /** Counts one more failed attempt on those of the events that are still unpublished. */
    public void recordFailedAttempt(Collection<UUID> ids) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1 WHERE id = ANY (:ids) AND published_at IS NULL")
                .param("ids", ids.toArray(UUID[]::new))
                .update();
    }

    /** How many events are waiting to be published. */
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    /** How long the oldest unpublished event has been waiting, or zero if none is. */
    public Duration oldestPendingAge() {
        double seconds = jdbc.sql("""
                        SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                        FROM outbox_events
                        WHERE published_at IS NULL
                        """).query(Double.class).single();
        return Duration.ofMillis(Math.max(0, Math.round(seconds * 1_000)));
    }

    private static OutboxRow toRow(ResultSet rs, int row) throws SQLException {
        return new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getString("topic"),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
