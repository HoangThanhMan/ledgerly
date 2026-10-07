package dev.ledgerly.outbox;

/**
 * Records events next to the change they report, so that an event exists exactly when its change was committed.
 * Publishing to Kafka happens later and separately: no caller ever waits for the broker.
 */
public interface OutboxWriter {

    /**
     * Adds the event to the current transaction. It is published after that transaction commits, and never if it
     * rolls back.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is open. An event
     *     written outside the transaction of its change could outlive a change that was rolled back.
     */
    void append(OutboxEvent event);
}
