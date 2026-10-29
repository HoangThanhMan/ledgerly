package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Anyone may browse events, signed in or not. Other tests add events too, so these never assume an empty list. */
class EventQueryIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;
    private final JdbcClient jdbc;
    private final TicketingApiDriver organizer;
    private final UUID payoutWallet;

    EventQueryIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.anonymous = client;
        this.jdbc = jdbc;
        RestTestClient signedIn = AuthDriver.signedIn(client, Role.ORGANIZER);
        this.organizer = new TicketingApiDriver(signedIn);
        this.payoutWallet = new WalletApiDriver(signedIn).openWallet();
    }

    @Test
    void listIsPagedAndOrderedByStartTime() {
        Instant base = Instant.now().plus(Duration.ofDays(400));
        UUID third = create(base.plus(Duration.ofDays(3)));
        UUID first = create(base.plus(Duration.ofDays(1)));
        UUID second = create(base.plus(Duration.ofDays(2)));

        Map<String, Object> page = list("?page=0&size=2");
        assertThat(items(page)).hasSize(2);
        assertThat(page).containsEntry("page", 0).containsEntry("size", 2);
        assertThat(((Number) requireNonNull(page.get("totalItems"))).longValue())
                .isGreaterThanOrEqualTo(3);

        List<Map<String, Object>> all = allEvents();
        assertThat(all)
                .extracting(event -> Instant.parse((String) event.get("startsAt")))
                .isSorted();
        assertThat(all).extracting(TicketingApiDriver::idOf).containsSubsequence(first, second, third);
    }

    @Test
    void listShowsTheCheapestPriceOfEachEvent() {
        UUID event = idOf(organizer.created(
                newEvent(payoutWallet, List.of(ticketClass("VIP", 2_000_000, 1), ticketClass("A", 500_000, 100)))));

        Map<String, Object> listed = allEvents().stream()
                .filter(item -> idOf(item).equals(event))
                .findFirst()
                .orElseThrow();

        assertThat(listed).containsEntry("minPrice", "500000").doesNotContainKey("ticketClasses");
    }

    @Test
    void eventsThatHaveStartedAreNotListed() {
        UUID started = create(Instant.now().plus(Duration.ofDays(10)));
        jdbc.sql("UPDATE events SET starts_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", started)
                .update();

        assertThat(allEvents()).extracting(TicketingApiDriver::idOf).doesNotContain(started);
    }

    @Test
    void sizeOutsideOneToFiftyIs400() {
        expectProblem(anonymous.get().uri("/v1/events?size=51").exchange(), HttpStatus.BAD_REQUEST, "validation-error");
        expectProblem(anonymous.get().uri("/v1/events?size=0").exchange(), HttpStatus.BAD_REQUEST, "validation-error");
        expectProblem(anonymous.get().uri("/v1/events?page=-1").exchange(), HttpStatus.BAD_REQUEST, "validation-error");
    }

    @Test
    void detailShowsLiveRemaining() {
        Map<String, Object> created = organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        jdbc.sql("UPDATE ticket_classes SET remaining = 6 WHERE id = :id")
                .param("id", classId(created, "A"))
                .update();

        Map<String, Object> event = body(anonymous
                .get()
                .uri("/v1/events/{id}", idOf(created))
                .exchange()
                .expectStatus()
                .isOk());

        assertThat(classOf(event, "A")).containsEntry("remaining", 6).containsEntry("totalQty", 10);
    }

    @Test
    void eventsArePublic() {
        UUID event = create(Instant.now().plus(Duration.ofDays(20)));

        anonymous.get().uri("/v1/events").exchange().expectStatus().isOk();
        anonymous.get().uri("/v1/events/{id}", event).exchange().expectStatus().isOk();
    }

    @Test
    void unknownEventIs404() {
        expectProblem(
                anonymous.get().uri("/v1/events/{id}", UUID.randomUUID()).exchange(),
                HttpStatus.NOT_FOUND,
                "event-not-found");
    }

    private UUID create(Instant startsAt) {
        return idOf(organizer.created(
                newEvent(payoutWallet, "Event " + UUID.randomUUID(), startsAt, List.of(ticketClass("A", 1_000, 10)))));
    }

    private Map<String, Object> list(String query) {
        return body(anonymous
                .get()
                .uri("/v1/events" + query)
                .exchange()
                .expectStatus()
                .isOk());
    }

    /** Every listed event, page by page. */
    private List<Map<String, Object>> allEvents() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<Map<String, Object>> items = items(list("?size=50&page=" + page));
            all.addAll(items);
            if (items.size() < 50) {
                return all;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) requireNonNull(page.get("items"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> classOf(Map<String, Object> event, String name) {
        return ((List<Map<String, Object>>) requireNonNull(event.get("ticketClasses")))
                .stream()
                        .filter(ticketClass -> name.equals(ticketClass.get("name")))
                        .findFirst()
                        .orElseThrow();
    }
}
