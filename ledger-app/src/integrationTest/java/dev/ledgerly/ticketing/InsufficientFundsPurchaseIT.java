package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.ticketing.TicketingApiDriver.Attempt;
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Buyers who cannot pay take the tickets for a moment and must give them all back: the class ends where it started,
 * even when they all try at once.
 */
class InsufficientFundsPurchaseIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final JdbcClient jdbc;

    InsufficientFundsPurchaseIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    @Test
    void poorBuyersLeaveTicketsUntouched() throws Exception {
        RestTestClient organizerClient = AuthDriver.signedIn(client, Role.ORGANIZER);
        TicketingApiDriver organizer = new TicketingApiDriver(organizerClient);
        UUID payoutWallet = new WalletApiDriver(organizerClient).openWallet();
        Map<String, Object> event = organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10))));
        UUID ticketClass = classId(event, "A");
        List<Buyer> buyers = Buyer.signUp(client, 50, 0);

        List<Attempt> answers = Together.run(
                buyers.stream()
                        .map(buyer -> (Callable<Attempt>)
                                () -> buyer.api().attempt(UUID.randomUUID().toString(), ticketClass, buyer.wallet(), 2))
                        .toList(),
                Duration.ofMinutes(2));

        assertThat(answers).extracting(Attempt::status).containsOnly(422);
        assertThat(answers).extracting(Attempt::problemType).containsOnly("insufficient-funds");
        assertThat(organizer.remaining(idOf(event), ticketClass)).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM ticket_orders WHERE ticket_class_id = :id")
                        .param("id", ticketClass)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(new InvariantChecker(jdbc).violations()).isEmpty();
    }
}
