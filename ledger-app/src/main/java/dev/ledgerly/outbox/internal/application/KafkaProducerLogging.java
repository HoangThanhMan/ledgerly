package dev.ledgerly.outbox.internal.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

/**
 * Keeps records out of the log when a send fails. Spring Boot's default listener logs the key and the start of the
 * value at ERROR, and the value is an event with wallets and amounts in it. The relay already logs every failed
 * send with the event id and the topic, which is all an operator needs to find the event in the outbox.
 */
@Configuration(proxyBeanMethods = false)
class KafkaProducerLogging {

    /** Takes the place of Spring Boot's {@code LoggingProducerListener}, which backs off when this bean exists. */
    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {
        return new ProducerListener<>() {};
    }
}
