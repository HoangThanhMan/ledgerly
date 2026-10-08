/**
 * Event contracts between {@code ledger-app} and its consumers.
 *
 * <p>Holds only plain Java records: the envelope, event payloads and topic names. No dependency on Spring or Jackson,
 * so any consumer can use them. A schema may only gain optional fields: a change of meaning or a removed field needs a
 * new event version and a new topic. Consumers ignore fields they do not know.
 *
 * <p>One sample of every event lives under {@code src/testFixtures/resources/contracts}. The producer's tests check
 * that it still writes that shape, the consumers' tests that they can still read it.
 */
@NullMarked
package dev.ledgerly.contracts;

import org.jspecify.annotations.NullMarked;
