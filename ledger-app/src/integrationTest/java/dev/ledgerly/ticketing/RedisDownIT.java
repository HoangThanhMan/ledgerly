package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis only makes things faster and limits greedy clients: a Redis that hangs must not stop anyone from browsing
 * events or buying tickets. Pausing the container is the hang: connections stay open and every command waits.
 *
 * <p>The container is shared by every test, so it is always resumed after each test.
 */
class RedisDownIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;
    private final GenericContainer<?> redis;
    private final MeterRegistry meters;
    private final TicketingApiDriver buyer;
    private final UUID wallet;
    private final Map<String, Object> event;
    private boolean paused;

    RedisDownIT(
            @Autowired RestTestClient client,
            @Autowired @Qualifier("redisContainer") GenericContainer<?> redis,
            @Autowired MeterRegistry meters) {
        this.anonymous = client;
        this.redis = redis;
        this.meters = meters;
        RestTestClient organizer = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizer).openWallet();
        this.event =
                new TicketingApiDriver(organizer).created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        RestTestClient signedIn = AuthDriver.signedIn(client, Role.USER);
        this.buyer = new TicketingApiDriver(signedIn);
        this.wallet = new WalletApiDriver(signedIn).openWalletWith(10_000);
    }

    @AfterEach
    void resumeRedis() {
        if (paused) {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
            paused = false;
        }
    }

    @Test
    void ordersStillWorkWhenRedisHangs() {
        double bypassed = bypassed();
        pauseRedis();

        long start = System.nanoTime();
        buyer.purchase(classId(event, "A"), wallet, 1).expectStatus().isCreated();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(bypassed()).isEqualTo(bypassed + 1);
    }

    @Test
    void eventsCanStillBeBrowsedWhenRedisHangs() {
        pauseRedis();

        anonymous.get().uri("/v1/events").exchange().expectStatus().isOk();
        anonymous
                .get()
                .uri("/v1/events/{id}", idOf(event))
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void healthStaysUpWhenRedisHangs() {
        pauseRedis();

        anonymous
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    private void pauseRedis() {
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        paused = true;
    }

    private double bypassed() {
        Counter counter = meters.find("ledgerly.ratelimit.bypassed").counter();
        return counter == null ? 0 : counter.count();
    }
}
