package dev.ledgerly.notification;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app against PostgreSQL and Kafka from Testcontainers, no compose needed:
 * {@code ./gradlew :notification-consumer:bootTestRun}.
 */
public class TestNotificationConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationConsumerApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
