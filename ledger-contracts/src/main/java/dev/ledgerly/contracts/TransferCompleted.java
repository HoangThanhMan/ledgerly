package dev.ledgerly.contracts;

import java.util.UUID;

/**
 * Money has moved from one wallet to another. The id of the ledger transaction is the aggregate id of the envelope.
 *
 * @param amount minor units of the currency as a string of digits, the same way the API writes amounts
 * @param currency ISO 4217 code
 */
public record TransferCompleted(UUID sourceWalletId, UUID targetWalletId, String amount, String currency) {

    public static final String EVENT_TYPE = "TransferCompleted";
    public static final int VERSION = 1;
    public static final String AGGREGATE_TYPE = "LedgerTransaction";
}
