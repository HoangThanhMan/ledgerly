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
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Purchases lock a ticket class and then two accounts, transfers lock only accounts, both in id order. Run together
 * on the same two wallets, in both directions, they must never wait for each other in a cycle.
 */
class PurchaseAndTransferIT extends AbstractIntegrationTest {

    private static final long PRICE = 100;
    private static final long AMOUNT = 10;
    private static final long FUNDS = 1_000_000;

    private final RestTestClient client;
    private final JdbcClient jdbc;

    PurchaseAndTransferIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    @Test
    void purchasesAndTransfersDoNotDeadlock() throws Exception {
        RestTestClient organizerClient = AuthDriver.signedIn(client, Role.ORGANIZER);
        WalletApiDriver organizerWallets = new WalletApiDriver(organizerClient);
        UUID payoutWallet = organizerWallets.openWalletWith(FUNDS);
        TicketingApiDriver organizer = new TicketingApiDriver(organizerClient);
        Map<String, Object> event = organizer.created(newEvent(payoutWallet, List.of(ticketClass("A", PRICE, 1_000))));
        UUID ticketClass = classId(event, "A");
        Buyer buyer = Buyer.signUp(client, 1, FUNDS).getFirst();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> buyer.api()
                    .attempt(UUID.randomUUID().toString(), ticketClass, buyer.wallet(), 1)
                    .status());
        }
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> status(buyer.wallets().transfer(buyer.wallet(), payoutWallet, Long.toString(AMOUNT))));
            tasks.add(() -> status(organizerWallets.transfer(payoutWallet, buyer.wallet(), Long.toString(AMOUNT))));
        }

        List<Integer> statuses = Together.run(tasks, Duration.ofMinutes(3));

        assertThat(statuses).containsOnly(201);
        assertThat(organizer.remaining(idOf(event), ticketClass)).isEqualTo(900);
        assertThat(buyer.wallets().balanceOf(buyer.wallet())).isEqualTo(Long.toString(FUNDS - 100 * PRICE));
        assertThat(organizerWallets.balanceOf(payoutWallet)).isEqualTo(Long.toString(FUNDS + 100 * PRICE));
        assertThat(new InvariantChecker(jdbc).violations()).isEmpty();
    }

    private static int status(RestTestClient.ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }
}
