package dev.ledgerly.ticketing.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An event to create, with its ticket classes.")
record CreateEventRequest(
        @Schema(description = "Name of the event, 1 to 200 characters.", example = "Ledgerly Live")
        @NotBlank @Size(max = 200) String name,

        @Schema(description = "When the event starts. Must be in the future.", example = "2027-03-01T12:00:00Z")
        @NotNull @Future Instant startsAt,

        @Schema(description = "One of the caller's wallets. The money of every ticket sold is paid into it.") @NotNull UUID payoutWalletId,

        @Schema(description = "1 to 10 ticket classes. They cannot be changed once the event exists.")
        @NotNull @Size(min = 1, max = 10) List<@Valid @NotNull TicketClassRequest> ticketClasses) {}
