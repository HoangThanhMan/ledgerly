package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

class EventManagementIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;
    private final JdbcClient jdbc;
    private final TicketingApiDriver organizer;
    private final UUID payoutWallet;

    EventManagementIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.anonymous = client;
        this.jdbc = jdbc;
        RestTestClient signedIn = AuthDriver.signedIn(client, Role.ORGANIZER);
        this.organizer = new TicketingApiDriver(signedIn);
        this.payoutWallet = new WalletApiDriver(signedIn).openWallet();
    }

    @Test
    void organizerCreatesAnEvent() {
        Instant startsAt = Instant.parse("2027-03-01T12:00:00Z");
        Map<String, Object> request = newEvent(
                payoutWallet,
                "Ledgerly Live",
                startsAt,
                List.of(ticketClass("A", 500_000, 1_000), ticketClass("VIP", 2_000_000, 1)));

        RestTestClient.ResponseSpec response = organizer.createEvent(request);

        Map<String, Object> event = WalletApiDriver.body(response.expectStatus().isCreated());
        response.expectHeader().location("/v1/events/" + idOf(event));
        assertThat(event)
                .containsEntry("name", "Ledgerly Live")
                .containsEntry("startsAt", startsAt.toString())
                .containsEntry("payoutWalletId", payoutWallet.toString());
        assertThat(remainingOf(classId(event, "A"))).isEqualTo(1_000);
        assertThat(remainingOf(classId(event, "VIP"))).isEqualTo(1);
    }

    @Test
    void userCannotCreateEvents() {
        RestTestClient user = AuthDriver.signedIn(anonymous, Role.USER);
        UUID wallet = new WalletApiDriver(user).openWallet();

        expectProblem(
                new TicketingApiDriver(user).createEvent(newEvent(wallet, List.of(ticketClass("A", 1_000, 10)))),
                HttpStatus.FORBIDDEN,
                "forbidden");
    }

    @Test
    void payoutWalletMustBeMine() {
        UUID someoneElses = new WalletApiDriver(AuthDriver.signedIn(anonymous, Role.USER)).openWallet();

        expectProblem(
                organizer.createEvent(newEvent(someoneElses, List.of(ticketClass("A", 1_000, 10)))),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "payout-wallet-invalid");
    }

    @Test
    void othersEventIs404() {
        UUID event = idOf(organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))));
        TicketingApiDriver other = new TicketingApiDriver(AuthDriver.signedIn(anonymous, Role.ORGANIZER));

        expectProblem(
                other.updateEvent(event, "Hijacked", Instant.now().plus(Duration.ofDays(5))),
                HttpStatus.NOT_FOUND,
                "event-not-found");
    }

    @Test
    void organizerRenamesAndReschedulesTheirEvent() {
        UUID event = idOf(organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))));
        Instant later = Instant.parse("2027-06-01T18:30:00Z");

        Map<String, Object> updated = WalletApiDriver.body(
                organizer.updateEvent(event, "Renamed", later).expectStatus().isOk());

        assertThat(updated).containsEntry("name", "Renamed").containsEntry("startsAt", later.toString());
    }

    /** Sales change remaining with SQL behind the entity's back: editing the event must not write it back. */
    @Test
    void editingEventDuringSalesKeepsRemaining() {
        Map<String, Object> event = organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        UUID ticketClass = classId(event, "A");
        RestTestClient buyer = AuthDriver.signedIn(anonymous, Role.USER);
        UUID wallet = new WalletApiDriver(buyer).openWalletWith(10_000);
        new TicketingApiDriver(buyer)
                .purchase(ticketClass, wallet, 3)
                .expectStatus()
                .isCreated();

        organizer
                .updateEvent(idOf(event), "Renamed", Instant.now().plus(Duration.ofDays(9)))
                .expectStatus()
                .isOk();

        assertThat(remainingOf(ticketClass)).isEqualTo(7);
    }

    @Test
    void ticketClassNamesAreUniqueWithinAnEvent() {
        expectProblem(
                organizer.createEvent(
                        newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10), ticketClass("A", 2_000, 5)))),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void priceMustBePositive() {
        expectProblem(
                organizer.createEvent(newEvent(payoutWallet, List.of(ticketClass("A", 0, 10)))),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void eventMustStartInTheFuture() {
        expectProblem(
                organizer.createEvent(newEvent(
                        payoutWallet,
                        "Past",
                        Instant.now().minus(Duration.ofDays(1)),
                        List.of(ticketClass("A", 1, 1)))),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    private int remainingOf(UUID ticketClass) {
        return jdbc.sql("SELECT remaining FROM ticket_classes WHERE id = :id")
                .param("id", ticketClass)
                .query(Integer.class)
                .single();
    }
}
