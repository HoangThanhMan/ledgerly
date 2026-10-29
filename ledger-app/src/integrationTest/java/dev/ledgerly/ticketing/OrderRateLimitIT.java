package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.ticketing.TicketingApiDriver.Attempt;
import dev.ledgerly.wallet.WalletApiDriver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Each user may send a limited number of orders a minute, counted in Redis. The limit is per user. */
@TestPropertySource(properties = "ledgerly.ticketing.rate-limit.per-minute=10")
class OrderRateLimitIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final StringRedisTemplate redis;
    private final UUID ticketClass;

    OrderRateLimitIT(@Autowired RestTestClient client, @Autowired StringRedisTemplate redis) {
        this.client = client;
        this.redis = redis;
        RestTestClient organizer = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizer).openWallet();
        this.ticketClass = classId(
                new TicketingApiDriver(organizer)
                        .created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 1_000)))),
                "A");
    }

    @Test
    void eleventhOrderInAMinuteIs429() {
        Customer customer = customer();
        for (int order = 1; order <= 10; order++) {
            assertThat(attempt(customer).status()).as("order %d", order).isEqualTo(201);
        }

        RestTestClient.ResponseSpec eleventh = customer.api().purchase(ticketClass, customer.wallet(), 1);

        eleventh.expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .value(
                        "Retry-After",
                        value -> assertThat(Integer.parseInt(value)).isBetween(1, 60))
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/rate-limited");
    }

    @Test
    void limitIsPerUser() {
        Customer greedy = customer();
        for (int order = 1; order <= 11; order++) {
            attempt(greedy);
        }

        assertThat(attempt(customer()).status()).isEqualTo(201);
    }

    @Test
    void limitKeyAlwaysHasTtl() {
        Customer customer = customer();

        attempt(customer);

        Long ttl = redis.getExpire("ratelimit:orders:" + customer.userId());
        assertThat(requireNonNull(ttl)).isBetween(1L, 60L);
    }

    private Customer customer() {
        String token = AuthDriver.register(client, Role.USER);
        RestTestClient signedIn = AuthDriver.withToken(client, token);
        return new Customer(
                AuthDriver.userIdOf(token),
                new TicketingApiDriver(signedIn),
                new WalletApiDriver(signedIn).openWalletWith(1_000_000));
    }

    private record Customer(UUID userId, TicketingApiDriver api, UUID wallet) {}

    private Attempt attempt(Customer customer) {
        return customer.api().attempt(UUID.randomUUID().toString(), ticketClass, customer.wallet(), 1);
    }
}
