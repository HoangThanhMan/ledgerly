package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.idempotency.IdempotentRequests;
import dev.ledgerly.idempotency.IdempotentRequests.Reply;
import dev.ledgerly.identity.Caller;
import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.ticketing.internal.application.OrderRateLimiter;
import dev.ledgerly.ticketing.internal.application.OrderService;
import dev.ledgerly.ticketing.internal.application.PurchaseResult;
import dev.ledgerly.ticketing.internal.application.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "Buy tickets and read the orders.")
@RestController
@RequestMapping("/v1/orders")
class OrderController {

    private final PurchaseService purchases;
    private final OrderService orders;
    private final IdempotentRequests idempotent;
    private final OrderRateLimiter limiter;

    OrderController(
            PurchaseService purchases, OrderService orders, IdempotentRequests idempotent, OrderRateLimiter limiter) {
        this.purchases = purchases;
        this.orders = orders;
        this.idempotent = idempotent;
        this.limiter = limiter;
    }

    @Operation(operationId = "createOrder", summary = "Buy tickets", description = """
            Buys 1 to 4 tickets of one class and pays for them from one of the caller's wallets, in a single \\
            database transaction: either the tickets are taken, the money is paid to the organizer and the order \\
            exists, or nothing changed at all. No two buyers can get the same last ticket.

            The call needs an `Idempotency-Key`. Sending it again with the same key never buys twice: it returns \\
            the stored response of the first attempt with `Idempotent-Replayed: true`. That holds for rejections \\
            too: a purchase refused with `sold-out` or `insufficient-funds` stays refused under its key.

            Each user may send a limited number of orders a minute (10 by default); more are refused with `rate-limited` before anything is \\
            done, so they can be retried with the same key once the minute is over.""")
    @ApiResponse(
            responseCode = "201",
            description = "The tickets are bought. Also the answer when a purchase is retried with the same key.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderResponse.class)),
            headers = {
                @Header(
                        name = "Location",
                        description = "Path of the order.",
                        schema = @Schema(type = "string", example = "/v1/orders/01a11ad9-7f10-7459-a273-deb24dbb4296")),
                @Header(
                        name = IdempotentRequests.REPLAYED_HEADER,
                        description = IdempotentRequests.REPLAYED_DOC,
                        schema = @Schema(type = "string", allowableValues = "true"))
            })
    @ProblemResponses({
        ProblemType.UNAUTHENTICATED,
        ProblemType.TICKET_CLASS_NOT_FOUND,
        ProblemType.WALLET_NOT_FOUND,
        ProblemType.SOLD_OUT,
        ProblemType.IDEMPOTENCY_IN_PROGRESS,
        ProblemType.IDEMPOTENCY_KEY_REUSED,
        ProblemType.QUANTITY_OUT_OF_RANGE,
        ProblemType.SALES_CLOSED,
        ProblemType.INSUFFICIENT_FUNDS,
        ProblemType.SAME_ACCOUNT_TRANSFER,
        ProblemType.BALANCE_LIMIT_EXCEEDED,
        ProblemType.RATE_LIMITED,
        ProblemType.OVERLOADED
    })
    @PostMapping
    ResponseEntity<String> create(
            Caller caller,
            @Parameter(description = IdempotentRequests.KEY_DOC, example = "3c9a7e10-5b2d-4f6e-9a1c-7d8e9f0a1b2c")
                    @RequestHeader(IdempotentRequests.KEY_HEADER)
                    @Pattern(regexp = IdempotentRequests.KEY_PATTERN, message = IdempotentRequests.KEY_MESSAGE) String idempotencyKey,
            @Valid @RequestBody PurchaseRequest request,
            HttpServletRequest http) {
        // Before the key is claimed: a limited order leaves nothing behind, and its retry is a new attempt.
        if (limiter.check(caller.userId()) instanceof OrderRateLimiter.Decision.Limited limited) {
            throw new ProblemException(ProblemType.RATE_LIMITED, "Too many orders this minute", limited.retryAfter());
        }
        return idempotent.execute(
                caller.userId(),
                idempotencyKey,
                http,
                request,
                id -> URI.create("/v1/orders/" + id),
                () -> purchase(caller, request));
    }

    @Operation(operationId = "listOrders", summary = "List my orders", description = """
            Returns the caller's orders, newest first, one page at a time. Orders of other users are never \
            listed.""")
    @ApiResponse(responseCode = "200", description = "One page of orders.")
    @ProblemResponses(ProblemType.UNAUTHENTICATED)
    @GetMapping
    OrderPageResponse list(
            Caller caller,
            @Parameter(description = "Number of the page, from 0.") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Largest number of orders in the page, from 1 to 50.")
                    @RequestParam(defaultValue = "20")
                    @Min(1) @Max(50) int size) {
        return OrderPageResponse.from(orders.page(caller.userId(), page, size));
    }

    @Operation(operationId = "getOrder", summary = "Read an order", description = """
            Returns one of the caller's orders. An order of another user answers `order-not-found`, just like one \
            that does not exist.""")
    @ApiResponse(responseCode = "200", description = "The order.")
    @ProblemResponses({ProblemType.UNAUTHENTICATED, ProblemType.ORDER_NOT_FOUND})
    @GetMapping("/{id}")
    OrderResponse get(Caller caller, @Parameter(description = "Id of the order.") @PathVariable UUID id) {
        return orders.find(id, caller.userId())
                .map(OrderResponse::from)
                .orElseThrow(
                        () -> new ProblemException(ProblemType.ORDER_NOT_FOUND, "Order " + id + " does not exist"));
    }

    private Reply purchase(Caller caller, PurchaseRequest request) {
        return switch (purchases.purchase(
                caller.userId(), request.walletId(), request.ticketClassId(), request.quantity())) {
            case PurchaseResult.Purchased purchased ->
                new Reply.Created(purchased.order().id(), OrderResponse.from(purchased.order()));
            case PurchaseResult.QuantityOutOfRange range ->
                rejected(ProblemType.QUANTITY_OUT_OF_RANGE, "Buy 1 to 4 tickets at once, not " + range.quantity());
            case PurchaseResult.TicketClassNotFound notFound ->
                rejected(
                        ProblemType.TICKET_CLASS_NOT_FOUND,
                        "Ticket class " + notFound.ticketClassId() + " does not exist");
            case PurchaseResult.SalesClosed closed ->
                rejected(ProblemType.SALES_CLOSED, "Event " + closed.eventId() + " has started");
            case PurchaseResult.WalletNotFound notFound ->
                rejected(ProblemType.WALLET_NOT_FOUND, "Wallet " + notFound.walletId() + " does not exist");
            case PurchaseResult.SameWallet same ->
                rejected(
                        ProblemType.SAME_ACCOUNT_TRANSFER,
                        "Wallet " + same.walletId() + " receives the money of these tickets and cannot pay for them");
            case PurchaseResult.SoldOut soldOut ->
                rejected(
                        ProblemType.SOLD_OUT,
                        "Ticket class " + soldOut.ticketClassId() + " has fewer tickets left than "
                                + request.quantity());
            case PurchaseResult.InsufficientFunds funds ->
                rejected(
                        ProblemType.INSUFFICIENT_FUNDS,
                        "Wallet " + funds.walletId() + " has " + funds.available() + ", the tickets cost "
                                + funds.requested());
            case PurchaseResult.BalanceLimitExceeded exceeded ->
                rejected(
                        ProblemType.BALANCE_LIMIT_EXCEEDED,
                        "The payment would take the balance of " + exceeded.accountId() + " past its limit");
        };
    }

    private static Reply rejected(ProblemType type, String detail) {
        return new Reply.Rejected(new ProblemException(type, detail));
    }
}
