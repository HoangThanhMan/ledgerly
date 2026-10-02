/**
 * Event contracts between {@code ledger-app} and its consumers.
 *
 * <p>Holds only plain Java records: the envelope, event payloads and topic names. No dependency on Spring or Jackson,
 * so any consumer can use them. Schema evolution rules: see {@code docs/01-kien-truc.md} section 9.
 */
@NullMarked
package dev.ledgerly.contracts;

import org.jspecify.annotations.NullMarked;
