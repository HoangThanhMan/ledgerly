package dev.ledgerly;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for {@code ledger-app} integration tests: full Spring context with real PostgreSQL and Kafka.
 *
 * <p>Spring caches contexts by configuration, so subclasses that add no configuration of their own share one context.
 * A subclass with its own configuration (e.g. {@code @MockitoBean}) gets a new context, but still shares the
 * containers through {@link TestcontainersConfiguration}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {}
