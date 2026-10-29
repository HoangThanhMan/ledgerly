package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.identity.Caller;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ticketing.internal.persistence.EventEntity;
import dev.ledgerly.ticketing.internal.persistence.EventJpaRepository;
import dev.ledgerly.ticketing.internal.persistence.TicketInventory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final EventCatalog catalog;

    EventService(EventJpaRepository events, TicketInventory inventory, LedgerApi ledger, EventCatalog catalog) {
        this.events = events;
        this.inventory = inventory;
        this.ledger = ledger;
        this.catalog = catalog;
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

    /** The event with the tickets left in each class, started or not. The tickets left are read now, never cached. */
    public Optional<EventDetails> find(UUID eventId) {
        return catalog.find(eventId).map(event -> new EventDetails(event, inventory.remainingOf(eventId)));
    }

    private EventDetails details(EventEntity event) {
        evictAfterCommit(event.id());
        return new EventDetails(EventCatalog.view(event), inventory.remainingOf(event.id()));
    }

    /** After the commit, not before: a read in between would cache the old event again. */
    private void evictAfterCommit(UUID eventId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                catalog.evict(eventId);
            }
        });
    }
}
