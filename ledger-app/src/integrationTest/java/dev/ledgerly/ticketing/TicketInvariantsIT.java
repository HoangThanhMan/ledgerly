package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.idOf;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.ledger.InvariantChecker;
import dev.ledgerly.wallet.WalletApiDriver;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * I8 and I9 tie the tickets to the money: tickets sold equal tickets ordered, and every order was paid with exactly its
 * amount. Each test breaks one of them inside a transaction it rolls back, and checks the invariant notices.
 */
class TicketInvariantsIT extends AbstractIntegrationTest {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final InvariantChecker checker;
    private final UUID ticketClass;
    private final UUID order;

    TicketInvariantsIT(
            @Autowired RestTestClient client, @Autowired JdbcClient jdbc, @Autowired TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.checker = new InvariantChecker(jdbc);
        RestTestClient organizer = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizer).openWallet();
        this.ticketClass = classId(
                new TicketingApiDriver(organizer).created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))),
                "A");
        RestTestClient buyer = AuthDriver.signedIn(client, Role.USER);
        UUID wallet = new WalletApiDriver(buyer).openWalletWith(10_000);
        this.order = idOf(body(new TicketingApiDriver(buyer)
                .purchase(ticketClass, wallet, 2)
                .expectStatus()
                .isCreated()));
    }

    @Test
    void salesThroughTheApiAreSound() {
        assertThat(checker.violations()).isEmpty();
    }

    @Test
    void ticketsGoneWithoutAnOrderViolateI8() {
        Map<String, List<Map<String, @Nullable Object>>> violations = inRolledBackTransaction(() -> {
            jdbc.sql("UPDATE ticket_classes SET remaining = remaining - 1 WHERE id = :id")
                    .param("id", ticketClass)
                    .update();
            return checker.violations();
        });

        assertThat(violations).containsOnlyKeys("I8");
        assertThat(violations.get("I8"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("id", ticketClass)
                        .containsEntry("sold", 3)
                        .containsEntry("ordered", 2L));
    }

    @Test
    void orderThatDoesNotMatchItsPaymentViolatesI9() {
        Map<String, List<Map<String, @Nullable Object>>> violations = inRolledBackTransaction(() -> {
            jdbc.sql("UPDATE ticket_orders SET amount = amount + 1 WHERE id = :id")
                    .param("id", order)
                    .update();
            return checker.violations();
        });

        assertThat(violations).containsOnlyKeys("I9");
        assertThat(violations.get("I9"))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("order_id", order));
    }

    private <T> T inRolledBackTransaction(Supplier<T> action) {
        return Objects.requireNonNull(transaction.execute(status -> {
            status.setRollbackOnly();
            return action.get();
        }));
    }
}
