package dev.ledgerly.shared.problem;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** The problem types of the API. */
public enum ProblemType {
    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "validation-error",
            "Request is not valid",
            "The body, a parameter or a header is missing or malformed. Nothing was done, and an Idempotency-Key"
                    + " sent with the request was not used up."),
    WALLET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "wallet-not-found",
            "Wallet not found",
            "No wallet has this id. System accounts are not wallets."),
    TRANSFER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "transfer-not-found",
            "Transfer not found",
            "No transfer has this id. The id of a deposit is not the id of a transfer."),
    EVENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "event-not-found",
            "Event not found",
            "No event has this id. When editing, an event of another organizer is reported the same way."),
    TICKET_CLASS_NOT_FOUND(
            HttpStatus.NOT_FOUND, "ticket-class-not-found", "Ticket class not found", "No ticket class has this id."),
    SOLD_OUT(
            HttpStatus.CONFLICT,
            "sold-out",
            "Not enough tickets left",
            "The ticket class has fewer tickets left than were asked for. Nothing was bought and no money moved."
                    + " The refusal is stored under the Idempotency-Key, so a new attempt needs a new key."),
    IDEMPOTENCY_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "idempotency-in-progress",
            "Request with this Idempotency-Key is still in progress",
            "An earlier request with the same Idempotency-Key has not finished. Wait for the time in Retry-After,"
                    + " then send the request again with the same key."),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "idempotency-key-reused",
            "Idempotency-Key was already used with a different request",
            "The Idempotency-Key was first sent with another method, path or body. Use a new key for every"
                    + " operation."),
    UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED,
            "unauthenticated",
            "Authentication required",
            "The request has no access token, or its token is expired or invalid. Sign in at /v1/auth/login and"
                    + " send the token as Authorization: Bearer <token>."),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "forbidden",
            "Not allowed for your role",
            "The caller is signed in, but their role may not do this. Only organizers create events."),
    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "invalid-credentials",
            "Email or password is wrong",
            "No user has this email, or the password does not match. The two cases get the same answer."),
    EMAIL_TAKEN(
            HttpStatus.CONFLICT,
            "email-taken",
            "Email is already registered",
            "A user with this email exists. Emails are compared regardless of case. Sign in instead."),
    INSUFFICIENT_FUNDS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "insufficient-funds",
            "Insufficient funds",
            "The source wallet holds less than the amount. No money moved. The rejection is stored under the"
                    + " Idempotency-Key, so a new attempt needs a new key."),
    SAME_ACCOUNT_TRANSFER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "same-account-transfer",
            "Source and target wallet are the same",
            "The source and the target of a transfer are the same wallet."),
    CURRENCY_MISMATCH(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "currency-mismatch",
            "Currency does not match the wallet",
            "The currency of the request is not the currency of a wallet it names."),
    BALANCE_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "balance-limit-exceeded",
            "Balance limit exceeded",
            "The amount would take a balance past the largest value the ledger can hold. No money moved."),
    PAYOUT_WALLET_INVALID(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "payout-wallet-invalid",
            "Payout wallet is not yours",
            "The wallet that should receive the money of the tickets is not one of the caller's wallets."),
    QUANTITY_OUT_OF_RANGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "quantity-out-of-range",
            "Quantity out of range",
            "An order buys 1 to 4 tickets."),
    SALES_CLOSED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "sales-closed",
            "Sales have closed",
            "The event has started, so its tickets are no longer for sale."),
    UNSUPPORTED_CURRENCY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "unsupported-currency",
            "Currency is not supported",
            "Wallets cannot be opened in this currency. Only VND is supported."),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal error",
            "Something unexpected failed. The cause is in the server log, not in the response. A request that"
                    + " moves money was rolled back and may be retried with the same Idempotency-Key."),
    OVERLOADED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "overloaded",
            "Service is overloaded",
            "The request could not get the locks it needs in time. Nothing was done. Wait for the time in"
                    + " Retry-After, then send the request again with the same Idempotency-Key.");

    private final HttpStatus status;
    private final URI uri;
    private final String slug;
    private final String title;
    private final String description;

    ProblemType(HttpStatus status, String slug, String title, String description) {
        this.status = status;
        this.uri = URI.create("/problems/" + slug);
        this.slug = slug;
        this.title = title;
        this.description = description;
    }

    public HttpStatus status() {
        return status;
    }

    /** The {@code type} member of the problem: a relative URI that identifies the problem type. */
    public URI uri() {
        return uri;
    }

    /** The last segment of {@link #uri()}: a short name for the problem type. */
    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    /** When the problem occurs and what a client should do about it, for the API documentation. */
    public String description() {
        return description;
    }

    /** A problem of this type. Its {@code instance} is left for whoever knows the request path. */
    public ProblemDetail toProblemDetail(@Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(uri);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
