package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
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

/** Far more buyers than tickets, all at once: every ticket is sold exactly once, and none is sold twice. */
class ConcurrentPurchaseIT extends AbstractIntegrationTest {

    private static final int TICKETS = 100;
    private static final int BUYERS = 500;
    private static final long PRICE = 1_000;

    private final RestTestClient client;
    private final JdbcClient jdbc;

    ConcurrentPurchaseIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    @Test
    void fiveHundredBuyersOneHundredTickets() throws Exception {
        RestTestClient organizerClient = AuthDriver.signedIn(client, Role.ORGANIZER);
        WalletApiDriver organizerWallets = new WalletApiDriver(organizerClient);
        TicketingApiDriver organizer = new TicketingApiDriver(organizerClient);
        UUID payoutWallet = organizerWallets.openWallet();
        Map<String, Object> event =
                organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", PRICE, TICKETS))));
        UUID ticketClass = classId(event, "A");
        List<Buyer> buyers = Buyer.signUp(client, BUYERS, 10_000);

        List<Attempt> answers = Together.run(
                buyers.stream()
                        .map(buyer -> (Callable<Attempt>)
                                () -> buyer.api().attempt(UUID.randomUUID().toString(), ticketClass, buyer.wallet(), 1))
                        .toList(),
                Duration.ofMinutes(3));

        Map<Integer, Long> statuses = answers.stream().collect(groupingBy(Attempt::status, counting()));
        assertThat(statuses).containsOnlyKeys(201, 409).containsEntry(201, (long) TICKETS);
        assertThat(answers)
                .filteredOn(answer -> answer.status() == 409)
                .extracting(Attempt::problemType)
                .containsOnly("sold-out");
        assertThat(organizer.remaining(idOf(event), ticketClass)).isZero();
        assertThat(ordersOf(ticketClass)).isEqualTo(TICKETS);
        assertThat(organizerWallets.balanceOf(payoutWallet)).isEqualTo(Long.toString(TICKETS * PRICE));
        assertThat(new InvariantChecker(jdbc).violations()).isEmpty();
    }

    private long ordersOf(UUID ticketClass) {
        return jdbc.sql("SELECT count(*) FROM ticket_orders WHERE ticket_class_id = :id")
                .param("id", ticketClass)
                .query(Long.class)
                .single();
    }
}
