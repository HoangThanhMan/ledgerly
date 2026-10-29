package dev.ledgerly.notification;

import dev.ledgerly.contracts.TicketPurchased;
import dev.ledgerly.contracts.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Tells buyers about their tickets. Failures are handled like the transfers' ({@link TransferEventListener}). */
@Component
class TicketEventListener {

    private final EventParser parser;
    private final NotificationService notifications;

    TicketEventListener(EventParser parser, NotificationService notifications) {
        this.parser = parser;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.TICKETS)
    void onMessage(String message) {
        parser.parse(message, TicketPurchased.EVENT_TYPE, TicketPurchased.class).ifPresent(notifications::handleTicket);
    }
}
