package dev.ledgerly.ticketing.internal.persistence;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** A row of {@code events} with its ticket classes. Only the name and the start time can change. */
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private @Nullable UUID id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(name = "payout_wallet_id", nullable = false, updatable = false)
    private UUID payoutWalletId;

    @Column(nullable = false)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.PERSIST)
    @OrderBy("price, name")
    private List<TicketClassEntity> ticketClasses = new ArrayList<>();

    @SuppressWarnings("NullAway.Init") // for JPA, which sets the fields
    protected EventEntity() {}

    public EventEntity(UUID organizerId, UUID payoutWalletId, String name, Instant startsAt) {
        this.organizerId = organizerId;
        this.payoutWalletId = payoutWalletId;
        this.name = name;
        this.startsAt = startsAt;
    }

    public void addTicketClass(String name, long price, int totalQty) {
        ticketClasses.add(new TicketClassEntity(this, name, price, totalQty));
    }

    public void reschedule(String name, Instant startsAt) {
        this.name = name;
        this.startsAt = startsAt;
    }

    /**
     * The id, assigned when the event is persisted.
     *
     * @throws NullPointerException before the event is persisted
     */
    public UUID id() {
        return requireNonNull(id, "event is not persisted yet");
    }

    public UUID organizerId() {
        return organizerId;
    }

    public UUID payoutWalletId() {
        return payoutWalletId;
    }

    public String name() {
        return name;
    }

    public Instant startsAt() {
        return startsAt;
    }

    /** Cheapest first. */
    public List<TicketClassEntity> ticketClasses() {
        return List.copyOf(ticketClasses);
    }
}
