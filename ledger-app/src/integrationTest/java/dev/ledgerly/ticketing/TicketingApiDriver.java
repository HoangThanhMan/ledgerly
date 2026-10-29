package dev.ledgerly.ticketing;

import static dev.ledgerly.wallet.WalletApiDriver.body;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Calls the event API as an organizer would, to arrange events and read them back. */
public final class TicketingApiDriver {

    private final RestTestClient client;

    /** @param client a client signed in as the user whose events these are */
    public TicketingApiDriver(RestTestClient client) {
        this.client = client;
    }

    /** A ticket class of a request that creates an event. */
    public static Map<String, Object> ticketClass(String name, long price, int totalQty) {
        return Map.of("name", name, "price", Long.toString(price), "totalQty", totalQty);
    }

    /** An event that starts in 30 days. */
    public static Map<String, Object> newEvent(UUID payoutWallet, List<Map<String, Object>> classes) {
        return newEvent(
                payoutWallet, "Event " + UUID.randomUUID(), Instant.now().plus(Duration.ofDays(30)), classes);
    }

    public static Map<String, Object> newEvent(
            UUID payoutWallet, String name, Instant startsAt, List<Map<String, Object>> classes) {
        return Map.of(
                "name",
                name,
                "startsAt",
                startsAt.toString(),
                "payoutWalletId",
                payoutWallet,
                "ticketClasses",
                classes);
    }

    public RestTestClient.ResponseSpec createEvent(Map<String, Object> request) {
        return client.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    /** Creates the event and returns it as the API answered. */
    public Map<String, Object> created(Map<String, Object> request) {
        return body(createEvent(request).expectStatus().isCreated());
    }

    public RestTestClient.ResponseSpec updateEvent(UUID eventId, String name, Instant startsAt) {
        return client.patch()
                .uri("/v1/events/{id}", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "startsAt", startsAt.toString()))
                .exchange();
    }

    public static UUID idOf(Map<String, Object> resource) {
        return UUID.fromString((String) requireNonNull(resource.get("id")));
    }

    /** The id of the event's ticket class with this name. */
    @SuppressWarnings("unchecked")
    public static UUID classId(Map<String, Object> event, String className) {
        List<Map<String, Object>> classes = (List<Map<String, Object>>) requireNonNull(event.get("ticketClasses"));
        return classes.stream()
                .filter(ticketClass -> className.equals(ticketClass.get("name")))
                .map(TicketingApiDriver::idOf)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ticket class " + className + " in " + event));
    }
}
