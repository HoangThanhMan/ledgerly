package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.contracts.TicketPurchased;
import dev.ledgerly.contracts.Topics;
import dev.ledgerly.ledger.AccountType;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingRequest;
import dev.ledgerly.ledger.PostingResult;
import dev.ledgerly.ledger.TransactionType;
import dev.ledgerly.outbox.OutboxEvent;
import dev.ledgerly.outbox.OutboxWriter;
import dev.ledgerly.shared.Money;
import dev.ledgerly.ticketing.internal.persistence.OrderRepository;
import dev.ledgerly.ticketing.internal.persistence.OrderRepository.OrderRow;
import dev.ledgerly.ticketing.internal.persistence.TicketClassSnapshot;
import dev.ledgerly.ticketing.internal.persistence.TicketInventory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sells tickets for money from a wallet, in one database transaction: the tickets are taken, the money moves from the
 * buyer's wallet to the organizer's, the order and its {@code TicketPurchased} event are written, or none of it
 * happens. Tickets and wallets live in one database, so no saga is needed.
 *
 * <p>Locks are always taken in the same order: the ticket class row first, then the accounts in id order (inside
 * {@link LedgerApi#post}). A transfer locks only accounts. No transaction locks an account and then a ticket class,
 * so purchases and transfers cannot wait for each other in a cycle.
 *
 * <p>A purchase refused after the tickets were taken, for lack of money, gives them back in the same transaction
 * instead of rolling it back: the refusal must commit, because it is stored under the request's idempotency key.
 */
@Service
public class PurchaseService {

    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 4;

    private static final Currency VND = Currency.getInstance("VND");

    private final TicketInventory inventory;
    private final OrderRepository orders;
    private final LedgerApi ledger;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    PurchaseService(
            TicketInventory inventory,
            OrderRepository orders,
            LedgerApi ledger,
            OutboxWriter outbox,
            MeterRegistry meters) {
        this.inventory = inventory;
        this.orders = orders;
        this.ledger = ledger;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Transactional
    public PurchaseResult purchase(UUID buyerId, UUID walletId, UUID ticketClassId, int quantity) {
        PurchaseResult result = decide(buyerId, walletId, ticketClassId, quantity);
        count(result);
        return result;
    }

    private PurchaseResult decide(UUID buyerId, UUID walletId, UUID ticketClassId, int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            return new PurchaseResult.QuantityOutOfRange(quantity);
        }
        Optional<TicketClassSnapshot> found = inventory.snapshot(ticketClassId);
        if (found.isEmpty()) {
            return new PurchaseResult.TicketClassNotFound(ticketClassId);
        }
        TicketClassSnapshot ticketClass = found.get();
        if (!ticketClass.startsAt().isAfter(Instant.now())) {
            return new PurchaseResult.SalesClosed(ticketClass.eventId());
        }
        boolean ownWallet = ledger.findAccount(walletId)
                .filter(account -> account.type() == AccountType.USER_WALLET)
                .filter(account -> buyerId.equals(account.ownerId()))
                .isPresent();
        if (!ownWallet) {
            return new PurchaseResult.WalletNotFound(walletId);
        }
        if (walletId.equals(ticketClass.payoutWalletId())) {
            // The ledger refuses a transaction that names one account twice, and rightly: it would move nothing.
            return new PurchaseResult.SameWallet(walletId);
        }
        Money amount = Money.of(Math.multiplyExact(ticketClass.price(), quantity), VND);

        OptionalInt remaining = inventory.tryReserve(ticketClassId, quantity);
        if (remaining.isEmpty()) {
            return new PurchaseResult.SoldOut(ticketClassId);
        }
        PostingRequest payment = new PostingRequest(
                TransactionType.TICKET_PURCHASE,
                null,
                List.of(new Posting(walletId, amount.negate()), new Posting(ticketClass.payoutWalletId(), amount)));
        return switch (ledger.post(payment)) {
            case PostingResult.Posted posted -> {
                OrderRow order = orders.insert(
                        ticketClassId, buyerId, walletId, quantity, amount.amount(), posted.transactionId());
                outbox.append(event(order, ticketClass, amount));
                yield new PurchaseResult.Purchased(order, remaining.getAsInt());
            }
            case PostingResult.InsufficientFunds funds -> {
                inventory.release(ticketClassId, quantity);
                yield new PurchaseResult.InsufficientFunds(walletId, funds.available(), funds.requested());
            }
            case PostingResult.BalanceLimitExceeded exceeded -> {
                inventory.release(ticketClassId, quantity);
                yield new PurchaseResult.BalanceLimitExceeded(exceeded.accountId());
            }
            case PostingResult.AccountNotFound notFound ->
                throw new IllegalStateException("account " + notFound.accountId() + " vanished during a purchase");
            case PostingResult.CurrencyMismatch mismatch ->
                throw new IllegalStateException("only VND wallets exist, yet the ledger reported " + mismatch);
        };
    }

    private static OutboxEvent event(OrderRow order, TicketClassSnapshot ticketClass, Money amount) {
        return new OutboxEvent(
                Topics.TICKETS,
                TicketPurchased.AGGREGATE_TYPE,
                order.id(),
                TicketPurchased.EVENT_TYPE,
                TicketPurchased.VERSION,
                new TicketPurchased(
                        order.id(),
                        ticketClass.eventId(),
                        ticketClass.eventName(),
                        ticketClass.id(),
                        ticketClass.name(),
                        order.quantity(),
                        Long.toString(amount.amount()),
                        amount.currency().getCurrencyCode(),
                        order.buyerId(),
                        order.walletId()));
    }

    /** Counts the outcome once the transaction has committed, like the transfers do. */
    private void count(PurchaseResult result) {
        String outcome = switch (result) {
            case PurchaseResult.Purchased purchased -> "purchased";
            case PurchaseResult.QuantityOutOfRange range -> "quantity_out_of_range";
            case PurchaseResult.TicketClassNotFound notFound -> "ticket_class_not_found";
            case PurchaseResult.SalesClosed closed -> "sales_closed";
            case PurchaseResult.WalletNotFound notFound -> "wallet_not_found";
            case PurchaseResult.SameWallet same -> "same_wallet";
            case PurchaseResult.SoldOut soldOut -> "sold_out";
            case PurchaseResult.InsufficientFunds funds -> "insufficient_funds";
            case PurchaseResult.BalanceLimitExceeded exceeded -> "balance_limit_exceeded";
        };
        Counter counter = Counter.builder("ledgerly.ticket.purchases")
                .description("Ticket purchases the service decided, by outcome")
                .tag("outcome", outcome)
                .register(meters);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }
}
