package dev.ledgerly.mockbank;

import org.springframework.boot.SpringApplication;

/** Runs the app against PostgreSQL from Testcontainers, no compose needed: {@code ./gradlew :mock-bank:bootTestRun}. */
public class TestMockBankApplication {

    public static void main(String[] args) {
        SpringApplication.from(MockBankApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
