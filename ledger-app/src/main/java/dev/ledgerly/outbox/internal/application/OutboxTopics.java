package dev.ledgerly.outbox.internal.application;

import dev.ledgerly.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics the relay publishes to, created at startup if the broker does not have them. If the broker is down at
 * that moment the application starts anyway, and the broker creates the topic on first use.
 */
@Configuration(proxyBeanMethods = false)
class OutboxTopics {

    /** Three partitions: consumers can scale to three instances, and events of one transfer still share one. */
    @Bean
    NewTopic transfersTopic() {
        return TopicBuilder.name(Topics.TRANSFERS).partitions(3).replicas(1).build();
    }
}
