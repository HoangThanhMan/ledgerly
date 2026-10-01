package dev.ledgerly;

import org.springframework.boot.SpringApplication;

/** Chạy app với PostgreSQL và Kafka từ Testcontainers, không cần compose: {@code ./gradlew :ledger-app:bootTestRun}. */
public class TestLedgerlyApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerlyApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
