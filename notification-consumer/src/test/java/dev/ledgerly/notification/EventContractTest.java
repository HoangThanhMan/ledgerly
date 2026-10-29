package dev.ledgerly.notification;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.TicketPurchased;
import dev.ledgerly.contracts.TransferCompleted;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer's side of the event contract: it can read the sample event that the producer's tests check their
 * output against, and it keeps reading it when the producer adds fields.
 */
class EventContractTest {

    private final EventParser parser = new EventParser(JsonMapper.builder().build());

    @Test
    void sampleEventOfTheContractIsReadCompletely() throws IOException {
        assertThat(parse(sample()))
                .contains(new EventEnvelope<>(
                        UUID.fromString("0199a7d0-1111-7aaa-8bbb-000000000001"),
                        "TransferCompleted",
                        1,
                        Instant.parse("2026-10-20T09:15:02.123456Z"),
                        "LedgerTransaction",
                        UUID.fromString("0199a7d0-2222-7aaa-8bbb-000000000002"),
                        new TransferCompleted(
                                UUID.fromString("0199a7c2-3333-7aaa-8bbb-000000000003"),
                                UUID.fromString("0199a7c2-4444-7aaa-8bbb-000000000004"),
                                "150000",
                                "VND")));
    }

    @Test
    void fieldsAddedByANewerProducerAreIgnored() throws IOException {
        String newer = sample().replace("\"eventVersion\": 1,", "\"eventVersion\": 1, \"traceId\": \"abc\",")
                .replace("\"currency\": \"VND\"", "\"currency\": \"VND\", \"note\": \"lunch\"");

        assertThat(newer).contains("traceId").contains("note");
        assertThat(parse(newer)).isEqualTo(parse(sample()));
    }

    @Test
    void eventOfAnotherTypeIsNotForThisConsumer() throws IOException {
        String other = sample().replace("\"TransferCompleted\"", "\"TransferReversed\"");

        assertThat(parse(other)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "[]",
                "{}",
                "{\"eventType\": \"TransferCompleted\"}",
                "{\"eventType\": \"TransferCompleted\", \"eventId\": \"0199a7d0-1111-7aaa-8bbb-000000000001\","
                        + " \"payload\": {\"sourceWalletId\": \"0199a7c2-3333-7aaa-8bbb-000000000003\"}}",
                "{\"eventType\": \"TransferCompleted\", \"eventId\": \"not-a-uuid\", \"payload\": {}}"
            })
    void messageThatIsNotAUsableEventIsRejectedAsMalformed(String message) {
        assertThatThrownBy(() -> parse(message)).isInstanceOf(MalformedEventException.class);
    }

    /**
     * JSON may leave out any field, and for most of them the parser simply produces null. These events are complete
     * in every other respect, so only the check for the missing field can reject them.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"eventId\": \"0199a7d0-1111-7aaa-8bbb-000000000001\",",
                "\"sourceWalletId\": \"0199a7c2-3333-7aaa-8bbb-000000000003\",",
                "\"targetWalletId\": \"0199a7c2-4444-7aaa-8bbb-000000000004\",",
                "\"amount\": \"150000\",",
                ",\n    \"currency\": \"VND\""
            })
    void eventThatLacksAFieldTheConsumerNeedsIsRejectedAsMalformed(String field) throws IOException {
        String incomplete = sample().replace(field, "");

        assertThat(incomplete).isNotEqualTo(sample());
        assertThatThrownBy(() -> parse(incomplete)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void eventWhosePayloadIsNullIsRejectedAsMalformed() throws IOException {
        String sample = sample();
        String withoutPayload = sample.substring(0, sample.indexOf("\"payload\"")) + "\"payload\": null\n}";

        assertThatThrownBy(() -> parse(withoutPayload)).isInstanceOf(MalformedEventException.class);
    }

    private Optional<EventEnvelope<TransferCompleted>> parse(String message) {
        return parser.parse(message, TransferCompleted.EVENT_TYPE, TransferCompleted.class);
    }

    private static String sample() throws IOException {
        try (InputStream in =
                requireNonNull(EventContractTest.class.getResourceAsStream("/contracts/transfer-completed.v1.json"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The ticket event of the contract maps onto its type field for field, so no field is lost or misnamed. */
    @Test
    void ticketPurchasedSampleMapsOntoTheContractType() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        String sample;
        try (InputStream in =
                requireNonNull(EventContractTest.class.getResourceAsStream("/contracts/ticket-purchased.v1.json"))) {
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        EventEnvelope<TicketPurchased> event = json.readValue(
                sample, json.getTypeFactory().constructParametricType(EventEnvelope.class, TicketPurchased.class));

        assertThat(event.eventType()).isEqualTo(TicketPurchased.EVENT_TYPE);
        assertThat(event.aggregateType()).isEqualTo(TicketPurchased.AGGREGATE_TYPE);
        assertThat(event.payload())
                .isEqualTo(new TicketPurchased(
                        UUID.fromString("0199a7d0-6666-7aaa-8bbb-000000000006"),
                        UUID.fromString("0199a7c2-7777-7aaa-8bbb-000000000007"),
                        "Ledgerly Live",
                        UUID.fromString("0199a7c2-8888-7aaa-8bbb-000000000008"),
                        "A",
                        2,
                        "1000000",
                        "VND",
                        UUID.fromString("0199a7c2-9999-7aaa-8bbb-000000000009"),
                        UUID.fromString("0199a7c2-aaaa-7aaa-8bbb-00000000000a")));
        assertThat(event.aggregateId()).isEqualTo(event.payload().orderId());
    }
}
