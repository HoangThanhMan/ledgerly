package dev.ledgerly.notification;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.contracts.EventEnvelope;
import dev.ledgerly.contracts.TransferCompleted;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

    private final TransferEventParser parser =
            new TransferEventParser(JsonMapper.builder().build());

    @Test
    void sampleEventOfTheContractIsReadCompletely() throws IOException {
        assertThat(parser.parse(sample()))
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
        assertThat(parser.parse(newer)).isEqualTo(parser.parse(sample()));
    }

    @Test
    void eventOfAnotherTypeIsNotForThisConsumer() throws IOException {
        String other = sample().replace("\"TransferCompleted\"", "\"TransferReversed\"");

        assertThat(parser.parse(other)).isEmpty();
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
        assertThatThrownBy(() -> parser.parse(message)).isInstanceOf(MalformedEventException.class);
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
        assertThatThrownBy(() -> parser.parse(incomplete)).isInstanceOf(MalformedEventException.class);
    }

    @Test
    void eventWhosePayloadIsNullIsRejectedAsMalformed() throws IOException {
        String sample = sample();
        String withoutPayload = sample.substring(0, sample.indexOf("\"payload\"")) + "\"payload\": null\n}";

        assertThatThrownBy(() -> parser.parse(withoutPayload)).isInstanceOf(MalformedEventException.class);
    }

    private static String sample() throws IOException {
        try (InputStream in =
                requireNonNull(EventContractTest.class.getResourceAsStream("/contracts/transfer-completed.v1.json"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
