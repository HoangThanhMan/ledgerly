package dev.ledgerly.outbox.internal.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * Reads a topic the plain way, with no deduplication, to see exactly what was published. It starts at the end of
 * the topic as it is when the reader is created, so records of earlier tests are not read.
 */
final class TopicReader implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(200);

    /** How long to keep listening after everything expected has arrived, to notice a copy too many. */
    private static final Duration LINGER = Duration.ofMillis(600);

    private final Consumer<String, String> consumer;

    TopicReader(ConsumerFactory<String, String> factory, String topic) {
        this.consumer = factory.createConsumer("topic-reader-" + UUID.randomUUID(), null);
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd only takes effect at the next poll or position call. Fix the positions now.
        partitions.forEach(consumer::position);
    }

    /**
     * The records with one of the given keys, in the order they were read. Returns once {@code expected} of them
     * have arrived and no further one followed, or when the timeout runs out.
     */
    List<ConsumerRecord<String, String>> read(Set<String> keys, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        long quietUntil = Long.MAX_VALUE;
        while (System.nanoTime() < Math.min(deadline, quietUntil)) {
            int before = records.size();
            consumer.poll(POLL).forEach(record -> {
                if (keys.contains(record.key())) {
                    records.add(record);
                }
            });
            if (records.size() >= expected && (records.size() > before || quietUntil == Long.MAX_VALUE)) {
                quietUntil = System.nanoTime() + LINGER.toNanos();
            }
        }
        return records;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
