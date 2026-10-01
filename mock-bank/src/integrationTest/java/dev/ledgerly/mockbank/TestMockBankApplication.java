package dev.ledgerly.mockbank;

import org.springframework.boot.SpringApplication;

/** Chạy app với PostgreSQL từ Testcontainers, không cần compose: {@code ./gradlew :mock-bank:bootTestRun}. */
public class TestMockBankApplication {

    public static void main(String[] args) {
        SpringApplication.from(MockBankApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
