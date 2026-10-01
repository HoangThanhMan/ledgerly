package dev.ledgerly.notification;

import org.springframework.boot.SpringApplication;

/**
 * Chạy app với PostgreSQL và Kafka từ Testcontainers, không cần compose:
 * {@code ./gradlew :notification-consumer:bootTestRun}.
 */
public class TestNotificationConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.from(NotificationConsumerApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
