package dev.ledgerly.ticketing.internal.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventJpaRepository extends JpaRepository<EventEntity, UUID> {

    /** Events that start after {@code now}, soonest first. Served by the index {@code events_starts_at_id}. */
    Page<EventEntity> findByStartsAtAfterOrderByStartsAtAscIdAsc(Instant now, Pageable page);

    boolean existsByOrganizerId(UUID organizerId);
}
