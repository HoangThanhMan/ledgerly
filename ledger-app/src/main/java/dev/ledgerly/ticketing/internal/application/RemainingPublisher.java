package dev.ledgerly.ticketing.internal.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells WebSocket subscribers how many tickets are left in a class. The message goes out after the transaction
 * commits, never inside it: subscribers never see a count that was rolled back, and the transaction never waits on
 * a socket while it holds the ticket class lock.
 *
 * <p>A message that cannot be sent is logged and dropped. The count is only a hint for the page; the database decides
 * every purchase.
 */
@Component
class RemainingPublisher {

    private static final Logger log = LoggerFactory.getLogger(RemainingPublisher.class);

    private final SimpMessagingTemplate broker;

    RemainingPublisher(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    void publishAfterCommit(UUID ticketClassId, int remaining) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    broker.convertAndSend(
                            "/topic/ticket-classes/" + ticketClassId, new RemainingMessage(ticketClassId, remaining));
                } catch (RuntimeException e) {
                    log.warn("Remaining tickets of {} not broadcast: {}", ticketClassId, e.toString());
                }
            }
        });
    }
}
