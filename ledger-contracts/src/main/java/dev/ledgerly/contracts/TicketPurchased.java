package dev.ledgerly.contracts;

import java.util.UUID;

/**
 * Tickets were bought and paid for from a wallet. The id of the order is the aggregate id of the envelope.
 *
 * @param amount minor units of the currency as a string of digits, the same way the API writes amounts
 * @param currency ISO 4217 code
 */
public record TicketPurchased(
        UUID orderId,
        UUID eventId,
        String eventName,
        UUID ticketClassId,
        String ticketClassName,
        int quantity,
        String amount,
        String currency,
        UUID buyerId,
        UUID buyerWalletId) {

    public static final String EVENT_TYPE = "TicketPurchased";
    public static final int VERSION = 1;
    public static final String AGGREGATE_TYPE = "TicketOrder";
}
