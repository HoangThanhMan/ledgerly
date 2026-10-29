package dev.ledgerly.contracts;

/** Kafka topic names. The suffix is the major version of the topic's event schemas. */
public final class Topics {

    /** Events about transfers between wallets, keyed by the id of the ledger transaction. */
    public static final String TRANSFERS = "ledgerly.transfers.v1";

    /** Events about ticket orders, keyed by the id of the order. */
    public static final String TICKETS = "ledgerly.tickets.v1";

    private Topics() {}
}
