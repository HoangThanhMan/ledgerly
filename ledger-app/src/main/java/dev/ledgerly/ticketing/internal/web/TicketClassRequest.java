package dev.ledgerly.ticketing.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "A class of tickets with one price, such as A, B or VIP.")
record TicketClassRequest(
        @Schema(description = "Unique within the event, 1 to 50 characters.", example = "VIP") @NotBlank @Size(max = 50) String name,

        @Schema(
                description = "Price of one ticket: a positive integer of VND, at most 1,000,000,000.",
                example = "2000000")
        @NotNull @Pattern(regexp = PRICE_PATTERN, message = "must be a positive integer of at most 10 digits") String price,

        @Schema(description = "Number of tickets, from 1 to 100,000.", example = "100") @Min(1) @Max(100_000) int totalQty) {

    static final String PRICE_PATTERN = "[1-9][0-9]{0,9}";

    static final long MAX_PRICE = 1_000_000_000L;
}
