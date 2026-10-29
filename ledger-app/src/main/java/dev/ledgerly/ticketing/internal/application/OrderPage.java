package dev.ledgerly.ticketing.internal.application;

import dev.ledgerly.ticketing.internal.persistence.OrderRepository.OrderRow;
import java.util.List;

/** One page of a buyer's orders, newest first. */
public record OrderPage(List<OrderRow> items, int page, int size, long totalItems) {

    public OrderPage {
        items = List.copyOf(items);
    }
}
