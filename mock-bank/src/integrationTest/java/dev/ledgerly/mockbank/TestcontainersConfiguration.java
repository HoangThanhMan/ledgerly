package dev.ledgerly.mockbank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shared by every integration test and by {@code bootTestRun}.
 *
 * <p>The containers are static constants, so each test JVM starts them only once, even when tests create several
 * Spring contexts: Spring calling {@code start()} again has no effect. Testcontainers (Ryuk) removes them when the
 * JVM exits.
 *
 * <p>Closing a context does stop them: Spring Boot closes every container bean of a closing context unless the bean
 * has a destroy method of its own. The other contexts would be left connected to containers that are gone, so
 * tests must not close their context ({@code @DirtiesContext}).
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return POSTGRES;
    }
}
