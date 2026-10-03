package dev.ledgerly.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every integration test and by {@code bootTestRun}.
 *
 * <p>The containers are static constants and the beans declare {@code destroyMethod = ""}, so each test JVM starts
 * them only once, even when tests create several Spring contexts: Spring calling {@code start()} again has no
 * effect, and closing a context does not stop the containers. Testcontainers (Ryuk) removes them when the JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
