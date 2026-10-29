package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.ticketing.TicketingApiDriver.Attempt;
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/** A buyer who clicks "buy" again, or whose network repeats the request, buys once and is answered the same way. */
class PurchaseIdempotencyIT extends AbstractIntegrationTest {

    private static final int MAX_ATTEMPTS = 30;

    private final RestTestClient client;
    private final JdbcClient jdbc;
    private UUID ticketClass;

    PurchaseIdempotencyIT(@Autowired RestTestClient client, @Autowired JdbcClient jdbc) {
        this.client = client;
        this.jdbc = jdbc;
        this.ticketClass = UUID.randomUUID();
    }

    @BeforeEach
    void aClassOfTenTickets() {
        RestTestClient organizer = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizer).openWallet();
        ticketClass = classId(
                new TicketingApiDriver(organizer).created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))),
                "A");
    }

    @Test
    void twentyConcurrentSendsOfOneKeyBuyOnce() throws Exception {
        Buyer buyer = Buyer.signUp(client, 1, 10_000).getFirst();
        String key = UUID.randomUUID().toString();
        Callable<Attempt> untilAnswered = () -> sendUntilAnswered(buyer, key);

        List<Attempt> answers = Together.run(Collections.nCopies(20, untilAnswered), Duration.ofMinutes(2));

        assertThat(answers).extracting(Attempt::status).containsOnly(201);
        assertThat(answers)
                .extracting(Attempt::body)
                .containsOnly(answers.getFirst().body());
        assertThat(answers).filteredOn(answer -> !answer.replayed()).hasSize(1);
        assertThat(ordersOf(ticketClass)).isOne();
        assertThat(buyer.wallets().balanceOf(buyer.wallet())).isEqualTo("9000");
    }

    /** The refusal is the answer to that key, even once the wallet could pay: a new attempt needs a new key. */
    @Test
    void retryAfterTopUpReplaysTheRejection() throws Exception {
        Buyer buyer = Buyer.signUp(client, 1, 0).getFirst();
        String key = UUID.randomUUID().toString();

        Attempt first = buyer.api().attempt(key, ticketClass, buyer.wallet(), 1);
        buyer.wallets().deposit(buyer.wallet(), 10_000);
        Attempt retry = buyer.api().attempt(key, ticketClass, buyer.wallet(), 1);

        assertThat(first.status()).isEqualTo(422);
        assertThat(first.problemType()).isEqualTo("insufficient-funds");
        assertThat(retry.status()).isEqualTo(422);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(ordersOf(ticketClass)).isZero();
        assertThat(buyer.wallets().balanceOf(buyer.wallet())).isEqualTo("10000");
    }

    /** Follows the retry protocol: while another send of the key is still running, wait and send again. */
    private Attempt sendUntilAnswered(Buyer buyer, String key) throws InterruptedException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Attempt answer = buyer.api().attempt(key, ticketClass, buyer.wallet(), 1);
            if (!"idempotency-in-progress".equals(answer.problemType())) {
                return answer;
            }
            Thread.sleep(Duration.ofSeconds(1));
        }
        throw new AssertionError("key " + key + " still in progress after " + MAX_ATTEMPTS + " attempts");
    }

    private long ordersOf(UUID ticketClass) {
        return jdbc.sql("SELECT count(*) FROM ticket_orders WHERE ticket_class_id = :id")
                .param("id", ticketClass)
                .query(Long.class)
                .single();
    }
}
