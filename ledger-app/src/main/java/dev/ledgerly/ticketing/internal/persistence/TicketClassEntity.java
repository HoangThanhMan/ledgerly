package dev.ledgerly.ticketing.internal.persistence;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code ticket_classes}. Every column is written once, when the event is created.
 *
 * <p>{@code remaining} above all must never be written from here: sales decrement it with SQL while an organizer may
 * be editing the event, and an entity that wrote back the value it loaded would undo those sales.
 */
@Entity
@Table(name = "ticket_classes")
public class TicketClassEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private @Nullable UUID id;

    @SuppressWarnings("UnusedVariable") // the owning side of EventEntity.ticketClasses: JPA writes event_id from it
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private EventEntity event;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private long price;

    @Column(name = "total_qty", nullable = false, updatable = false)
    private int totalQty;

    /** The value when the class was created. Read the current one from {@link TicketInventory}. */
    @Column(nullable = false, updatable = false)
    private int remaining;

    @SuppressWarnings("NullAway.Init") // for JPA, which sets the fields
    protected TicketClassEntity() {}

    TicketClassEntity(EventEntity event, String name, long price, int totalQty) {
        this.event = event;
        this.name = name;
        this.price = price;
        this.totalQty = totalQty;
        this.remaining = totalQty;
    }

    /**
     * The id, assigned when the ticket class is persisted.
     *
     * @throws NullPointerException before the ticket class is persisted
     */
    public UUID id() {
        return requireNonNull(id, "ticket class is not persisted yet");
    }

    public String name() {
        return name;
    }

    public long price() {
        return price;
    }

    public int totalQty() {
        return totalQty;
    }
}
