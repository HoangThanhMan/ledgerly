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
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/** Buying tickets moves the money and the tickets in one transaction, or neither of them. */
class PurchaseApiIT extends AbstractIntegrationTest {

    private static final long PRICE = 1_000;

    private final RestTestClient anonymous;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final RestTestClient organizerClient;
    private final TicketingApiDriver organizer;
    private final WalletApiDriver organizerWallets;
    private final TicketingApiDriver buyer;
    private final WalletApiDriver buyerWallets;

    private UUID payoutWallet;
    private UUID event;
    private UUID classA;

    PurchaseApiIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc, @Autowired JsonMapper json) {
        this.anonymous = client;
        this.jdbc = jdbc;
        this.json = json;
        this.organizerClient = AuthDriver.signedIn(client, Role.ORGANIZER);
        this.organizer = new TicketingApiDriver(organizerClient);
        this.organizerWallets = new WalletApiDriver(organizerClient);
        RestTestClient buyerClient = AuthDriver.signedIn(client, Role.USER);
        this.buyer = new TicketingApiDriver(buyerClient);
        this.buyerWallets = new WalletApiDriver(buyerClient);
    }

    @BeforeEach
    void anEventWithTenTicketsOfClassA() {
        payoutWallet = organizerWallets.openWallet();
        Map<String, Object> created = organizer.created(
                newEvent(payoutWallet, List.of(ticketClass("A", PRICE, 10), ticketClass("VIP", 5_000, 1))));
        event = idOf(created);
        classA = classId(created, "A");
    }

    @Test
    void purchaseMovesMoneyAndTickets() throws IOException {
        UUID wallet = buyerWallets.openWalletWith(10_000);

        RestTestClient.ResponseSpec response = buyer.purchase(classA, wallet, 2);

        Map<String, Object> order = body(response.expectStatus().isCreated());
        UUID orderId = idOf(order);
        response.expectHeader().location("/v1/orders/" + orderId);
        assertThat(order)
                .containsEntry("eventId", event.toString())
                .containsEntry("ticketClassId", classA.toString())
                .containsEntry("walletId", wallet.toString())
                .containsEntry("quantity", 2)
                .containsEntry("amount", "2000")
                .containsEntry("currency", "VND")
                .containsKeys("ledgerTransactionId", "createdAt");
        assertThat(buyerWallets.balanceOf(wallet)).isEqualTo("8000");
        assertThat(organizerWallets.balanceOf(payoutWallet)).isEqualTo("2000");
        assertThat(organizer.remaining(event, classA)).isEqualTo(8);
        assertThat(publishedPayloadKeysOf(orderId)).isEqualTo(samplePayloadKeys());
    }

    @Test
    void soldOutIs409AndChangesNothing() {
        UUID vip = classId(eventDetails(), "VIP");
        UUID wallet = buyerWallets.openWalletWith(100_000);

        expectProblem(buyer.purchase(vip, wallet, 2), HttpStatus.CONFLICT, "sold-out");

        assertThat(buyerWallets.balanceOf(wallet)).isEqualTo("100000");
        assertThat(organizer.remaining(event, vip)).isEqualTo(1);
    }

    @Test
    void insufficientFundsKeepsTickets() {
        UUID wallet = buyerWallets.openWalletWith(500);

        expectProblem(buyer.purchase(classA, wallet, 1), HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds");

        assertThat(organizer.remaining(event, classA)).isEqualTo(10);
        assertThat(ordersOf(classA)).isZero();
        assertThat(buyerWallets.balanceOf(wallet)).isEqualTo("500");
    }

    @Test
    void quantityOutOfRange() {
        UUID wallet = buyerWallets.openWalletWith(100_000);

        expectProblem(buyer.purchase(classA, wallet, 0), HttpStatus.UNPROCESSABLE_CONTENT, "quantity-out-of-range");
        expectProblem(buyer.purchase(classA, wallet, 5), HttpStatus.UNPROCESSABLE_CONTENT, "quantity-out-of-range");
        assertThat(organizer.remaining(event, classA)).isEqualTo(10);
    }

    @Test
    void othersWalletIs404() {
        UUID someoneElses = new WalletApiDriver(AuthDriver.signedIn(anonymous, Role.USER)).openWalletWith(10_000);

        expectProblem(buyer.purchase(classA, someoneElses, 1), HttpStatus.NOT_FOUND, "wallet-not-found");
    }

    @Test
    void unknownTicketClassIs404() {
        UUID wallet = buyerWallets.openWalletWith(10_000);

        expectProblem(buyer.purchase(UUID.randomUUID(), wallet, 1), HttpStatus.NOT_FOUND, "ticket-class-not-found");
    }

    @Test
    void startedEventIs422SalesClosed() {
        UUID wallet = buyerWallets.openWalletWith(10_000);
        jdbc.sql("UPDATE events SET starts_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", event)
                .update();

        expectProblem(buyer.purchase(classA, wallet, 1), HttpStatus.UNPROCESSABLE_CONTENT, "sales-closed");
        assertThat(buyerWallets.balanceOf(wallet)).isEqualTo("10000");
    }

    /** The ledger refuses a transaction that names one account twice: this must be a 422, not a 500. */
    @Test
    void buyingWithThePayoutWalletIsRejected() {
        expectProblem(
                organizer.purchase(classA, payoutWallet, 1), HttpStatus.UNPROCESSABLE_CONTENT, "same-account-transfer");
        assertThat(organizer.remaining(event, classA)).isEqualTo(10);
    }

    @Test
    void missingIdempotencyKeyIs400() {
        UUID wallet = buyerWallets.openWalletWith(10_000);

        expectProblem(
                AuthDriver.signedIn(anonymous, Role.USER)
                        .post()
                        .uri("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("ticketClassId", classA, "walletId", wallet, "quantity", 1))
                        .exchange(),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void ordersNeedASignedInBuyer() {
        expectProblem(
                anonymous
                        .post()
                        .uri("/v1/orders")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("ticketClassId", classA, "walletId", UUID.randomUUID(), "quantity", 1))
                        .exchange(),
                HttpStatus.UNAUTHORIZED,
                "unauthenticated");
    }

    private Map<String, Object> eventDetails() {
        return body(anonymous
                .get()
                .uri("/v1/events/{id}", event)
                .exchange()
                .expectStatus()
                .isOk());
    }

    private long ordersOf(UUID ticketClass) {
        return jdbc.sql("SELECT count(*) FROM ticket_orders WHERE ticket_class_id = :id")
                .param("id", ticketClass)
                .query(Long.class)
                .single();
    }

    /** The producer's side of the contract: the event written for the order has the payload of the sample. */
    @SuppressWarnings("unchecked")
    private java.util.Set<String> publishedPayloadKeysOf(UUID orderId) {
        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox_events
                        WHERE aggregate_id = :id AND event_type = 'TicketPurchased' AND topic = 'ledgerly.tickets.v1'
                        """).param("id", orderId).query(String.class).single();
        return json.readValue(payload, Map.class).keySet();
    }

    @SuppressWarnings("unchecked")
    private java.util.Set<String> samplePayloadKeys() throws IOException {
        try (InputStream in =
                requireNonNull(PurchaseApiIT.class.getResourceAsStream("/contracts/ticket-purchased.v1.json"))) {
            Map<String, Object> sample = json.readValue(in, Map.class);
            return ((Map<String, Object>) requireNonNull(sample.get("payload"))).keySet();
        }
    }
}
