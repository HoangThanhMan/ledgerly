package dev.ledgerly;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

/**
 * Base class for {@code ledger-app} integration tests: full Spring context with real PostgreSQL and Kafka, served
 * over HTTP on a random port. Tests that call the API inject a {@code RestTestClient} bound to that port.
 *
 * <p>Spring caches contexts by configuration, so subclasses that add no configuration of their own share one context.
 * A subclass with its own configuration (e.g. {@code @MockitoBean}) gets a new context, but still shares the
 * containers through {@link TestcontainersConfiguration}. *
 * <p>The outbox relay is not scheduled. Contexts stay cached, and so alive, for the whole test run: a relay in each
 * would publish the events of whichever test happens to be running. Tests that are about the relay call it
 * directly, and the one test of the scheduler cancels its relay when it is done.
 *
 * <p>Never close a context during the run, so no {@code @DirtiesContext}: when a context closes, Spring Boot stops
 * the containers it holds as beans, and those are the containers every other context is still connected to.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "ledgerly.outbox.relay.enabled=false")
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {}
