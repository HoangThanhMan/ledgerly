package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.identity.Caller;
import dev.ledgerly.identity.IdentityApi;
import dev.ledgerly.identity.Role;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ticketing.internal.persistence.EventJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demo organizer with one event at startup, when {@code ledgerly.demo.seed} is true, so that a fresh system
 * has something to buy. Does nothing if the demo organizer has an event already.
 */
@Component
@ConditionalOnProperty(name = "ledgerly.demo.seed", havingValue = "true")
public class DemoSeeder implements ApplicationRunner {

    public static final String ORGANIZER_EMAIL = "organizer@ledgerly.dev";
    public static final String ORGANIZER_PASSWORD = "organizer-demo";

    private final IdentityApi identity;
    private final LedgerApi ledger;
    private final EventJpaRepository events;
    private final EventService eventService;

    DemoSeeder(IdentityApi identity, LedgerApi ledger, EventJpaRepository events, EventService eventService) {
        this.identity = identity;
        this.ledger = ledger;
        this.events = events;
        this.eventService = eventService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** One transaction: a failure leaves no organizer without an event and no stray payout wallet. */
    @Transactional
    public void seed() {
        UUID organizer = identity.findOrRegister(ORGANIZER_EMAIL, ORGANIZER_PASSWORD, Role.ORGANIZER);
        if (events.existsByOrganizerId(organizer)) {
            return;
        }
        UUID payoutWallet =
                ledger.openAccount(Currency.getInstance("VND"), organizer).id();
        CreateEventResult result = eventService.create(
                new Caller(organizer, Role.ORGANIZER),
                "Ledgerly Live",
                Instant.now().plus(Duration.ofDays(30)),
                payoutWallet,
                List.of(
                        new NewTicketClass("A", 500_000, 1_000),
                        new NewTicketClass("B", 300_000, 500),
                        new NewTicketClass("VIP", 2_000_000, 1)));
        if (!(result instanceof CreateEventResult.Created)) {
            throw new IllegalStateException("seeding the demo event failed: " + result);
        }
    }
}
