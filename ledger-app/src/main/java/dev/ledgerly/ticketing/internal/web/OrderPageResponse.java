package dev.ledgerly.ticketing.internal.web;

import dev.ledgerly.ticketing.internal.application.OrderPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of the caller's orders, newest first.")
record OrderPageResponse(
        @Schema(description = "The orders of this page.") List<OrderResponse> items,

        @Schema(description = "Number of this page, from 0.", example = "0")
        int page,

        @Schema(description = "Largest number of orders in a page.", example = "20")
        int size,

        @Schema(description = "Number of orders on all pages together.", example = "3")
        long totalItems) {

    static OrderPageResponse from(OrderPage page) {
        return new OrderPageResponse(
                page.items().stream().map(OrderResponse::from).toList(), page.page(), page.size(), page.totalItems());
    }
}
