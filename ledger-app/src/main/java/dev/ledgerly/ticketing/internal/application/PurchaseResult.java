package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.shared.Money;
import dev.ledgerly.ticketing.internal.persistence.OrderRepository.OrderRow;
import java.util.UUID;

/** Outcome of a purchase. Every rejection left both the tickets and the money as they were. */
public sealed interface PurchaseResult {

    /**
     * The tickets are bought and paid for.
     *
     * @param remaining tickets left in the class right after this purchase
     */
    record Purchased(OrderRow order, int remaining) implements PurchaseResult {}

    /** Only 1 to 4 tickets can be bought at once. */
    record QuantityOutOfRange(int quantity) implements PurchaseResult {}

    record TicketClassNotFound(UUID ticketClassId) implements PurchaseResult {}

    /** The event has started: its tickets are no longer for sale. */
    record SalesClosed(UUID eventId) implements PurchaseResult {}

    /** No such wallet, or not one of the buyer's. */
    record WalletNotFound(UUID walletId) implements PurchaseResult {}

    /** The buyer would pay with the wallet that receives the money. */
    record SameWallet(UUID walletId) implements PurchaseResult {}

    /** Fewer tickets are left than were asked for. */
    record SoldOut(UUID ticketClassId) implements PurchaseResult {}

    record InsufficientFunds(UUID walletId, Money available, Money requested) implements PurchaseResult {}

    record BalanceLimitExceeded(UUID accountId) implements PurchaseResult {}
}
