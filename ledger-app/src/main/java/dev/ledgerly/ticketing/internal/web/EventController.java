package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.identity.Caller;
import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import dev.ledgerly.ticketing.internal.application.CreateEventResult;
import dev.ledgerly.ticketing.internal.application.EventService;
import dev.ledgerly.ticketing.internal.application.NewTicketClass;
import dev.ledgerly.ticketing.internal.application.UpdateEventResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Events", description = "Events and their ticket classes.")
@RestController
@RequestMapping("/v1/events")
class EventController {

    private final EventService events;

    EventController(EventService events) {
        this.events = events;
    }

    @Operation(operationId = "createEvent", summary = "Create an event", description = """
            Creates an event with its ticket classes. Only organizers create events, and the money of every \\
            ticket sold is paid into `payoutWalletId`, which must be one of the caller's wallets. Ticket classes \\
            cannot be changed afterwards: every class starts with all its tickets for sale.""")
    @ApiResponse(
            responseCode = "201",
            description = "The event was created.",
            headers =
                    @Header(
                            name = "Location",
                            description = "Path of the new event.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            example = "/v1/events/01a11ad9-7f10-7459-a273-deb24dbb4296")))
    @ProblemResponses({ProblemType.UNAUTHENTICATED, ProblemType.FORBIDDEN, ProblemType.PAYOUT_WALLET_INVALID})
    @PostMapping
    ResponseEntity<EventResponse> create(Caller caller, @Valid @RequestBody CreateEventRequest request) {
        List<NewTicketClass> classes = ticketClasses(request.ticketClasses());
        return switch (events.create(caller, request.name(), request.startsAt(), request.payoutWalletId(), classes)) {
            case CreateEventResult.Created created ->
                ResponseEntity.created(URI.create(
                                "/v1/events/" + created.event().event().id()))
                        .body(EventResponse.from(created.event()));
            case CreateEventResult.Forbidden forbidden ->
                throw new ProblemException(ProblemType.FORBIDDEN, "Only organizers create events");
            case CreateEventResult.PayoutWalletInvalid invalid ->
                throw new ProblemException(
                        ProblemType.PAYOUT_WALLET_INVALID,
                        "Wallet " + invalid.walletId() + " is not one of your wallets");
        };
    }

    @Operation(operationId = "listEvents", summary = "List upcoming events", description = """
            Returns the events that have not started yet, soonest first, one page at a time. Each event shows the \
            price of its cheapest ticket. No sign-in needed.""")
    @ApiResponse(responseCode = "200", description = "One page of events.")
    @GetMapping
    EventPageResponse list(
            @Parameter(description = "Number of the page, from 0.") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Largest number of events in the page, from 1 to 50.")
                    @RequestParam(defaultValue = "20")
                    @Min(1) @Max(50) int size) {
        return EventPageResponse.from(events.upcoming(page, size));
    }

    @Operation(operationId = "getEvent", summary = "Read an event", description = """
            Returns an event with its ticket classes and the tickets left in each, read at the time of the \
            request. Events that have started can still be read. No sign-in needed.""")
    @ApiResponse(responseCode = "200", description = "The event.")
    @ProblemResponses(ProblemType.EVENT_NOT_FOUND)
    @GetMapping("/{id}")
    EventResponse get(@Parameter(description = "Id of the event.") @PathVariable UUID id) {
        return events.find(id)
                .map(EventResponse::from)
                .orElseThrow(
                        () -> new ProblemException(ProblemType.EVENT_NOT_FOUND, "Event " + id + " does not exist"));
    }

    @Operation(operationId = "updateEvent", summary = "Rename or reschedule an event", description = """
            Changes the name and the start of one of the caller's events. Ticket classes, prices and the payout \\
            wallet stay as they are. An event of another organizer answers `event-not-found`, just like one \\
            that does not exist.""")
    @ApiResponse(responseCode = "200", description = "The event as it is now.")
    @ProblemResponses({ProblemType.UNAUTHENTICATED, ProblemType.EVENT_NOT_FOUND})
    @PatchMapping("/{id}")
    EventResponse update(
            Caller caller,
            @Parameter(description = "Id of the event, as returned when it was created.") @PathVariable UUID id,
            @Valid @RequestBody UpdateEventRequest request) {
        return switch (events.update(caller, id, request.name(), request.startsAt())) {
            case UpdateEventResult.Updated updated -> EventResponse.from(updated.event());
            case UpdateEventResult.NotFound notFound ->
                throw new ProblemException(ProblemType.EVENT_NOT_FOUND, "Event " + id + " does not exist");
        };
    }

    /** Checks what the annotations cannot: prices up to the limit, and names unique within the event. */
    private static List<NewTicketClass> ticketClasses(List<TicketClassRequest> requests) {
        Set<String> names = new HashSet<>();
        for (TicketClassRequest request : requests) {
            if (!names.add(request.name())) {
                throw new ProblemException(
                        ProblemType.VALIDATION_ERROR, "Two ticket classes are named " + request.name());
            }
        }
        return requests.stream()
                .map(request -> {
                    long price = Long.parseLong(request.price());
                    if (price > TicketClassRequest.MAX_PRICE) {
                        throw new ProblemException(
                                ProblemType.VALIDATION_ERROR,
                                "Price of ticket class " + request.name() + " is above "
                                        + TicketClassRequest.MAX_PRICE);
                    }
                    return new NewTicketClass(request.name(), price, request.totalQty());
                })
                .toList();
    }
}
