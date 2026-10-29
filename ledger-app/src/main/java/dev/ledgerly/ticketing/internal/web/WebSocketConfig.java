package dev.ledgerly.ticketing.internal.web;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket at {@code /ws}, with an in-memory broker for {@code /topic}. Browsers subscribe to
 * {@code /topic/ticket-classes/{id}} to see the tickets left in a class change as they are sold.
 *
 * <p>The broker lives in this process, so a subscriber only hears about purchases made by the instance it is connected
 * to. Clients only listen: a message a client sends is dropped, or anyone could broadcast made-up counts to everyone
 * watching.
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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
                return SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE
                        ? null
                        : message;
            }
        });
    }
}
