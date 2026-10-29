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
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    /** A page of the events that have not started yet, soonest first. */
    @Transactional(readOnly = true)
    public EventPage upcoming(int page, int size) {
        Page<EventEntity> found =
                events.findByStartsAtAfterOrderByStartsAtAscIdAsc(Instant.now(), PageRequest.of(page, size));
        List<EventSummary> items = found.stream().map(EventService::summary).toList();
        return new EventPage(items, page, size, found.getTotalElements());
    }

    /** The event with the tickets left in each class, started or not. */
    @Transactional(readOnly = true)
    public Optional<EventDetails> find(UUID eventId) {
        return events.findById(eventId).map(this::details);
    }

    private static EventSummary summary(EventEntity event) {
        long minPrice = event.ticketClasses().stream()
                .mapToLong(TicketClassEntity::price)
                .min()
                .orElseThrow(() -> new IllegalStateException("event " + event.id() + " has no ticket classes"));
        return new EventSummary(event.id(), event.name(), event.startsAt(), minPrice);
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
