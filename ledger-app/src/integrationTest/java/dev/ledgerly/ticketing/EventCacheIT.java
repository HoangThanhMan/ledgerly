package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Event reads are served from Redis: the event list and the fixed part of an event (name, start, ticket classes,
 * prices). The tickets left are never cached, and an edit is visible on the next read.
 */
class EventCacheIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;
    private final MeterRegistry meters;
    private final TicketingApiDriver organizer;
    private final UUID payoutWallet;

    EventCacheIT(@Autowired RestTestClient client, @Autowired MeterRegistry meters) {
        this.anonymous = client;
        this.meters = meters;
        RestTestClient signedIn = AuthDriver.signedIn(client, Role.ORGANIZER);
        this.organizer = new TicketingApiDriver(signedIn);
        this.payoutWallet = new WalletApiDriver(signedIn).openWallet();
    }

    @Test
    void secondListCallIsAHit() {
        organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        list();
        double hits = hits("events-page");

        list();

        assertThat(hits("events-page")).isEqualTo(hits + 1);
    }

    @Test
    void secondDetailCallIsAHit() {
        UUID event = idOf(organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))));
        detail(event);
        double hits = hits("event");

        detail(event);

        assertThat(hits("event")).isEqualTo(hits + 1);
    }

    @Test
    void editEvictsTheEvent() {
        UUID event = idOf(organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))));
        detail(event);
        Instant startsAt = Instant.now().plus(Duration.ofDays(40));
        String listedBefore = nameInList(event);

        organizer.updateEvent(event, "Renamed", startsAt).expectStatus().isOk();

        assertThat(detail(event)).containsEntry("name", "Renamed");
        assertThat(listedBefore).isNotEqualTo("Renamed");
        assertThat(nameInList(event)).isEqualTo("Renamed");
    }

    @Test
    void remainingIsNeverCached() {
        Map<String, Object> created = organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        UUID event = idOf(created);
        UUID classA = classId(created, "A");
        RestTestClient buyer = AuthDriver.signedIn(anonymous, Role.USER);
        TicketingApiDriver buyerApi = new TicketingApiDriver(buyer);
        UUID wallet = new WalletApiDriver(buyer).openWalletWith(10_000);
        assertThat(buyerApi.remaining(event, classA)).isEqualTo(10);

        buyerApi.purchase(classA, wallet, 1).expectStatus().isCreated();

        assertThat(buyerApi.remaining(event, classA)).isEqualTo(9);
    }

    private double hits(String cache) {
        FunctionCounter counter = meters.find("cache.gets")
                .tag("cache", cache)
                .tag("result", "hit")
                .functionCounter();
        return requireNonNull(counter, "no cache.gets meter for " + cache).count();
    }

    private Map<String, Object> list() {
        return body(anonymous
                .get()
                .uri("/v1/events?page=0&size=50")
                .exchange()
                .expectStatus()
                .isOk());
    }

    @SuppressWarnings("unchecked")
    private String nameInList(UUID event) {
        for (int page = 0; ; page++) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) requireNonNull(body(anonymous
                            .get()
                            .uri("/v1/events?page={page}&size=50", page)
                            .exchange()
                            .expectStatus()
                            .isOk())
                    .get("items"));
            assertThat(items).as("event %s in the list", event).isNotEmpty();
            for (Map<String, Object> item : items) {
                if (idOf(item).equals(event)) {
                    return (String) requireNonNull(item.get("name"));
                }
            }
        }
    }

    private Map<String, Object> detail(UUID event) {
        return body(anonymous
                .get()
                .uri("/v1/events/{id}", event)
                .exchange()
                .expectStatus()
                .isOk());
    }
}
