package dev.ledgerly.notification;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.TransferCompleted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService {

    private final NotificationRepository repository;
    private final Counter duplicates;

    NotificationService(NotificationRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.duplicates = Counter.builder("notification.duplicates")
                .description("Deliveries dropped because their event had been handled before")
                .register(meters);
    }

    /**
     * Tells the receiving wallet about the transfer, once per event.
     *
     * <p>The mark that the event was handled and the notification are written in one transaction. If it fails,
     * neither exists and the redelivery starts from scratch. If it commits, every later delivery of the event finds
     * the mark and does nothing. The offset is committed to Kafka only after this method has returned.
     */
    @Transactional
    void handle(EventEnvelope<TransferCompleted> event) {
        if (!repository.markProcessed(event.eventId(), event.eventType())) {
            duplicates.increment();
            return;
        }
        TransferCompleted transfer = event.payload();
        repository.insertNotification(
                transfer.targetWalletId(),
                "You received " + transfer.amount() + " " + transfer.currency() + " from wallet "
                        + transfer.sourceWalletId());
    }
}
