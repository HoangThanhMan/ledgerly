package dev.ledgerly.notification;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NotificationRepository {

    private final JdbcClient jdbc;

    NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Remembers the event as handled.
     *
     * @return false if it was remembered before, which makes this delivery a duplicate
     */
    boolean markProcessed(UUID eventId, String eventType) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_events (event_id, event_type)
                        VALUES (:eventId, :eventType)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update();
        return inserted == 1;
    }

    void insertNotification(UUID accountId, String message) {
        jdbc.sql("INSERT INTO notifications (account_id, message) VALUES (:accountId, :message)")
                .param("accountId", accountId)
                .param("message", message)
                .update();
    }
}
