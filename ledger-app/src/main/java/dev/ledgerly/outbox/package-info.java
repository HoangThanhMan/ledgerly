/**
 * Transactional outbox: events are written in the same transaction as the business change, then relayed to
 * Kafka with {@code FOR UPDATE SKIP LOCKED}.
 *
 * <p>The public API lives at the package root. Everything under {@code internal} is private to the module.
 */
@NullMarked
package dev.ledgerly.outbox;

import org.jspecify.annotations.NullMarked;
