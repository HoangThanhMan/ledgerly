package dev.ledgerly.outbox.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.contracts.Topics;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;

/** A record Kafka refuses must not end up in the log: its value is an event with wallets and amounts in it. */
@ExtendWith(OutputCaptureExtension.class)
class FailedSendLoggingIT extends AbstractIntegrationTest {

    private final KafkaTemplate<String, String> kafka;

    FailedSendLoggingIT(@Autowired KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Test
    void failedSendDoesNotLogTheRecord(CapturedOutput output) {
        String amount = "987654321987";
        // Over the producer's 1 MB limit, so the send fails without reaching the broker.
        String value = "{\"amount\":\"" + amount + "\",\"padding\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";

        assertThatThrownBy(() -> kafka.send(Topics.TRANSFERS, UUID.randomUUID().toString(), value)
                        .get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(RecordTooLargeException.class);

        assertThat(output).doesNotContain(amount);
    }
}
