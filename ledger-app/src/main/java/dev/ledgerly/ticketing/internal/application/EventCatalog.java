package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.ticketing.internal.persistence.EventEntity;
import dev.ledgerly.ticketing.internal.persistence.EventJpaRepository;
import dev.ledgerly.ticketing.internal.persistence.TicketClassEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The parts of events that only the organizer changes, cached: the event list and each event without its remaining
 * tickets. Sales never change what is cached here, so a purchase evicts nothing.
 *
 * <p>An edit evicts after it commits. A read that started before the commit can still put the old version back,
 * and it stays until it expires ({@link CacheConfig#TTL}).
 */
@Component
public class EventCatalog {

    private final EventJpaRepository events;

    EventCatalog(EventJpaRepository events) {
        this.events = events;
    }

    /** A page of the events that have not started yet, soonest first. An event can stay listed until the page expires. */
    @Cacheable(cacheNames = CacheConfig.EVENTS_PAGE, key = "#page + ':' + #size")
    @Transactional(readOnly = true)
    public EventPage page(int page, int size) {
        Page<EventEntity> found =
                events.findByStartsAtAfterOrderByStartsAtAscIdAsc(Instant.now(), PageRequest.of(page, size));
        List<EventSummary> items = found.stream().map(EventCatalog::summary).toList();
        return new EventPage(items, page, size, found.getTotalElements());
    }

    @Cacheable(cacheNames = CacheConfig.EVENT, key = "#eventId", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<EventView> find(UUID eventId) {
        return events.findById(eventId).map(EventCatalog::view);
    }

    /** Drops the event, and every page since any of them may list it. */
    @Caching(
            evict = {
                @CacheEvict(cacheNames = CacheConfig.EVENT, key = "#eventId"),
                @CacheEvict(cacheNames = CacheConfig.EVENTS_PAGE, allEntries = true)
            })
    public void evict(UUID eventId) {
        // The annotations do the work.
    }

    static EventView view(EventEntity event) {
        List<TicketClassView> classes =
                event.ticketClasses().stream().map(EventCatalog::view).toList();
        return new EventView(
                event.id(), event.organizerId(), event.payoutWalletId(), event.name(), event.startsAt(), classes);
    }

    private static TicketClassView view(TicketClassEntity ticketClass) {
        return new TicketClassView(ticketClass.id(), ticketClass.name(), ticketClass.price(), ticketClass.totalQty());
    }

    private static EventSummary summary(EventEntity event) {
        long minPrice = event.ticketClasses().stream()
                .mapToLong(TicketClassEntity::price)
                .min()
                .orElseThrow(() -> new IllegalStateException("event " + event.id() + " has no ticket classes"));
        return new EventSummary(event.id(), event.name(), event.startsAt(), minPrice);
    }
}
