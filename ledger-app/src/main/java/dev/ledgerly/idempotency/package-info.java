/**
 * Two-phase {@code Idempotency-Key} handling (claim, then complete): replays, body conflicts, keys still in
 * progress, recovery after a crash.
 *
 * <p>The public API lives at the package root. Everything under {@code internal} is private to the module.
 */
@NullMarked
package dev.ledgerly.idempotency;

import org.jspecify.annotations.NullMarked;
