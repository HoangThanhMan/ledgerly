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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/** A buyer sees their own orders, and nobody else's. */
class OrderQueryIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;
    private final RestTestClient buyerClient;
    private final TicketingApiDriver buyer;
    private final UUID ticketClass;
    private final UUID wallet;

    OrderQueryIT(@Autowired RestTestClient client) {
        this.anonymous = client;
        RestTestClient organizerClient = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizerClient).openWallet();
        Map<String, Object> event = new TicketingApiDriver(organizerClient)
                .created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 100))));
        this.ticketClass = classId(event, "A");
        this.buyerClient = AuthDriver.signedIn(client, Role.USER);
        this.buyer = new TicketingApiDriver(buyerClient);
        this.wallet = new WalletApiDriver(buyerClient).openWalletWith(100_000);
    }

    @Test
    void myOrdersNewestFirst() {
        UUID first = buy(1);
        UUID second = buy(2);
        UUID third = buy(3);

        Map<String, Object> page = body(
                buyerClient.get().uri("/v1/orders").exchange().expectStatus().isOk());

        assertThat(items(page)).extracting(TicketingApiDriver::idOf).containsExactly(third, second, first);
        assertThat(page).containsEntry("totalItems", 3);
        assertThat(items(page).getFirst()).containsEntry("quantity", 3).containsEntry("amount", "3000");
    }

    @Test
    void anOrderCanBeReadBack() {
        UUID order = buy(2);

        Map<String, Object> read = body(buyerClient
                .get()
                .uri("/v1/orders/{id}", order)
                .exchange()
                .expectStatus()
                .isOk());

        assertThat(read)
                .containsEntry("id", order.toString())
                .containsEntry("ticketClassId", ticketClass.toString())
                .containsEntry("quantity", 2);
    }

    @Test
    void othersOrderIs404() {
        UUID order = buy(1);
        RestTestClient someoneElse = AuthDriver.signedIn(anonymous, Role.USER);

        expectProblem(
                someoneElse.get().uri("/v1/orders/{id}", order).exchange(), HttpStatus.NOT_FOUND, "order-not-found");
        assertThat(items(body(someoneElse
                        .get()
                        .uri("/v1/orders")
                        .exchange()
                        .expectStatus()
                        .isOk())))
                .isEmpty();
    }

    @Test
    void sizeAbove50Is400() {
        expectProblem(
                buyerClient.get().uri("/v1/orders?size=51").exchange(), HttpStatus.BAD_REQUEST, "validation-error");
    }

    private UUID buy(int quantity) {
        return idOf(body(
                buyer.purchase(ticketClass, wallet, quantity).expectStatus().isCreated()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) requireNonNull(page.get("items"));
    }
}
