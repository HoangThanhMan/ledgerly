package dev.ledgerly.ticketing;

import static dev.ledgerly.ticketing.TicketingApiDriver.classId;
import static dev.ledgerly.ticketing.TicketingApiDriver.newEvent;
import static dev.ledgerly.ticketing.TicketingApiDriver.ticketClass;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Anyone watching a ticket class over STOMP at {@code /ws} hears its new remaining count once a purchase has
 * committed, and hears nothing about a purchase that was refused.
 */
class RemainingBroadcastIT extends AbstractIntegrationTest {

    /** What a subscriber reads from {@code /topic/ticket-classes/{id}}. */
    record Remaining(UUID ticketClassId, int remaining) {}

    /** A message the test sends itself, to learn that the subscription is in place. */
    private static final int PROBE = -1;

    private final RestTestClient client;
    private final SimpMessagingTemplate broker;
    private final int port;
    private final WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
    private final UUID ticketClass;

    private @Nullable StompSession session;

    RemainingBroadcastIT(
            @Autowired RestTestClient client, @Autowired SimpMessagingTemplate broker, @LocalServerPort int port) {
        this.client = client;
        this.broker = broker;
        this.port = port;
        this.stomp.setMessageConverter(new JacksonJsonMessageConverter());
        RestTestClient organizer = AuthDriver.signedIn(client, Role.ORGANIZER);
        UUID payoutWallet = new WalletApiDriver(organizer).openWallet();
        this.ticketClass = classId(
                new TicketingApiDriver(organizer).created(newEvent(payoutWallet, List.of(ticketClass("A", 1_000, 10)))),
                "A");
    }

    @AfterEach
    void disconnect() {
        if (session != null) {
            session.disconnect();
        }
    }

    @Test
    void purchaseBroadcastsNewRemaining() throws Exception {
        BlockingQueue<Remaining> heard = watch(ticketClass);
        RestTestClient buyer = AuthDriver.signedIn(client, Role.USER);
        UUID wallet = new WalletApiDriver(buyer).openWalletWith(10_000);

        new TicketingApiDriver(buyer)
                .purchase(ticketClass, wallet, 2)
                .expectStatus()
                .isCreated();

        assertThat(heard.poll(5, TimeUnit.SECONDS)).isEqualTo(new Remaining(ticketClass, 8));
    }

    @Test
    void rejectedPurchaseBroadcastsNothing() throws Exception {
        BlockingQueue<Remaining> heard = watch(ticketClass);
        RestTestClient buyer = AuthDriver.signedIn(client, Role.USER);
        UUID emptyWallet = new WalletApiDriver(buyer).openWallet();

        new TicketingApiDriver(buyer)
                .purchase(ticketClass, emptyWallet, 2)
                .expectStatus()
                .isEqualTo(422);

        assertThat(heard.poll(1, TimeUnit.SECONDS)).isNull();
    }

    /** Subscribes to the ticket class and returns once the subscription is live, with nothing in the queue. */
    private BlockingQueue<Remaining> watch(UUID id) throws Exception {
        StompSession connected = stomp.connectAsync(
                        "ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        session = connected;
        BlockingQueue<Remaining> heard = new LinkedBlockingQueue<>();
        CountDownLatch live = new CountDownLatch(1);
        String topic = "/topic/ticket-classes/" + id;
        connected.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Remaining.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                Remaining remaining = (Remaining) requireNonNull(payload);
                if (remaining.remaining() == PROBE) {
                    live.countDown();
                } else {
                    heard.add(remaining);
                }
            }
        });
        // SUBSCRIBE is handled asynchronously: send probes until one comes back. Probes never reach the queue.
        for (int attempt = 0; attempt < 50; attempt++) {
            broker.convertAndSend(topic, new Remaining(id, PROBE));
            if (live.await(100, TimeUnit.MILLISECONDS)) {
                return heard;
            }
        }
        throw new AssertionError("subscription to " + topic + " never became live");
    }
}
