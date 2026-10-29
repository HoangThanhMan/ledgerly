package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.identity.Caller;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ticketing.internal.persistence.EventEntity;
import dev.ledgerly.ticketing.internal.persistence.EventJpaRepository;
import dev.ledgerly.ticketing.internal.persistence.TicketClassEntity;
import dev.ledgerly.ticketing.internal.persistence.TicketInventory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organizers create and edit their events. An event belongs to the organizer who created it: to anyone else, editing
 * it looks exactly like editing an event that does not exist.
 *
 * <p>Ticket classes are fixed once the event exists. Changing a price or a quantity after tickets were sold would need
 * rules for the tickets already sold, and no such rules exist.
 */
@Service
public class EventService {

    private final EventJpaRepository events;
    private final TicketInventory inventory;
    private final LedgerApi ledger;

    EventService(EventJpaRepository events, TicketInventory inventory, LedgerApi ledger) {
        this.events = events;
        this.inventory = inventory;
        this.ledger = ledger;
    }

    /**
     * Creates an event with all its tickets for sale.
     *
     * @param payoutWalletId one of the caller's wallets, which receives the money of every ticket sold
     */
    @Transactional
    public CreateEventResult create(
            Caller caller, String name, Instant startsAt, UUID payoutWalletId, List<NewTicketClass> classes) {
        if (!caller.isOrganizer()) {
            return new CreateEventResult.Forbidden();
        }
        boolean ownWallet = ledger.findAccount(payoutWalletId)
                .filter(account -> account.type() == AccountType.USER_WALLET)
                .filter(account -> caller.userId().equals(account.ownerId()))
                .isPresent();
        if (!ownWallet) {
            return new CreateEventResult.PayoutWalletInvalid(payoutWalletId);
        }
        EventEntity event = new EventEntity(caller.userId(), payoutWalletId, name, startsAt);
        for (NewTicketClass ticketClass : classes) {
            event.addTicketClass(ticketClass.name(), ticketClass.price(), ticketClass.totalQty());
        }
        // Flushed, so that the inventory, which reads with plain SQL, sees the new ticket classes.
        return new CreateEventResult.Created(details(events.saveAndFlush(event)));
    }

    @Transactional
    public UpdateEventResult update(Caller caller, UUID eventId, String name, Instant startsAt) {
        return events.findById(eventId)
                .filter(event -> event.organizerId().equals(caller.userId()))
                .<UpdateEventResult>map(event -> {
                    event.reschedule(name, startsAt);
                    return new UpdateEventResult.Updated(details(event));
                })
                .orElseGet(UpdateEventResult.NotFound::new);
    }

    private EventDetails details(EventEntity event) {
        return new EventDetails(view(event), inventory.remainingOf(event.id()));
    }

    static EventView view(EventEntity event) {
        List<TicketClassView> classes =
                event.ticketClasses().stream().map(EventService::view).toList();
        return new EventView(
                event.id(), event.organizerId(), event.payoutWalletId(), event.name(), event.startsAt(), classes);
    }

    private static TicketClassView view(TicketClassEntity ticketClass) {
        return new TicketClassView(ticketClass.id(), ticketClass.name(), ticketClass.price(), ticketClass.totalQty());
    }
}
