package dev.ledgerly.ticketing.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Schema(description = "The new name and start of an event.")
record UpdateEventRequest(
        @Schema(description = "Name of the event, 1 to 200 characters.", example = "Ledgerly Live")
        @NotBlank @Size(max = 200) String name,

        @Schema(description = "When the event starts. Must be in the future.", example = "2027-03-01T12:00:00Z")
        @NotNull @Future Instant startsAt) {}
