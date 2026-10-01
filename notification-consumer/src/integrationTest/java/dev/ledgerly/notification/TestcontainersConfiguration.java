package dev.ledgerly.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Container dùng chung cho mọi integration test và cho {@code bootTestRun}.
 *
 * <p>Container là hằng số static, và bean khai báo {@code destroyMethod = ""}. Vì vậy mỗi JVM test chỉ khởi động
 * một lần, kể cả khi các test tạo ra nhiều Spring context khác nhau: Spring gọi {@code start()} lần nữa thì không có
 * tác dụng, và không dừng container khi đóng context. Testcontainers (Ryuk) dọn container khi JVM kết thúc.
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
