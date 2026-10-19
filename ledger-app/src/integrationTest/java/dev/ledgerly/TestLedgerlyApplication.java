package dev.ledgerly;

import org.springframework.boot.SpringApplication;

/**
 * Runs the app against PostgreSQL and Kafka from Testcontainers, no compose needed:
 * {@code ./gradlew :ledger-app:bootTestRun}.
 */
public class TestLedgerlyApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerlyApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
