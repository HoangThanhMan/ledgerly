package dev.ledgerly.ticketing.internal.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket at {@code /ws}, with an in-memory broker for {@code /topic}. Browsers subscribe to
 * {@code /topic/ticket-classes/{id}} to see the tickets left in a class change as they are sold.
 *
 * <p>The broker lives in this process, so a subscriber only hears about purchases made by the instance it is connected
 * to. Clients only listen: nothing they send is handled.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
