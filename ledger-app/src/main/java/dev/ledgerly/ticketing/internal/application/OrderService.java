package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.ticketing.internal.persistence.OrderRepository;
import dev.ledgerly.ticketing.internal.persistence.OrderRepository.OrderRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A buyer's orders. To anyone else an order is absent, exactly like one that does not exist. */
@Service
public class OrderService {

    private final OrderRepository orders;

    OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    /** One transaction, so the page and the count agree. */
    @Transactional(readOnly = true)
    public OrderPage page(UUID buyerId, int page, int size) {
        return new OrderPage(orders.page(buyerId, page, size), page, size, orders.countOf(buyerId));
    }

    @Transactional(readOnly = true)
    public Optional<OrderRow> find(UUID orderId, UUID buyerId) {
        return orders.find(orderId, buyerId);
    }
}
