package dev.ledgerly.notification;

import dev.ledgerly.contracts.Topics;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.ExponentialBackOff;

@Component
class TransferEventListener {

    private final TransferEventParser parser;
    private final NotificationService notifications;

    TransferEventListener(TransferEventParser parser, NotificationService notifications) {
        this.parser = parser;
        this.notifications = notifications;
    }

    @KafkaListener(topics = Topics.TRANSFERS)
    void onMessage(String message) {
        parser.parse(message).ifPresent(notifications::handle);
    }

    /**
     * What happens when handling a message fails.
     *
     * <p>A message that cannot be read is logged and skipped at once: it would fail the same way forever and block
     * every event behind it on its partition. Any other failure, typically the database being away, is retried
     * with a growing pause and without limit. Skipping there would lose a notification for an event that is fine.
     */
    @Bean
    static CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff untilItWorks = new ExponentialBackOff(1_000, 2);
        untilItWorks.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(untilItWorks);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}
