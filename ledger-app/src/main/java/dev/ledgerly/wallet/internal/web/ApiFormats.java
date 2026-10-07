package dev.ledgerly.wallet.internal.web;

import dev.ledgerly.shared.Money;

/** Wire formats shared by the wallet endpoints. */
final class ApiFormats {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** 1 to 64 visible ASCII characters. A UUID is what clients are expected to send. */
    static final String IDEMPOTENCY_KEY_PATTERN = "[\\x21-\\x7E]{1,64}";

    static final String IDEMPOTENCY_KEY_MESSAGE = "must be 1 to 64 visible ASCII characters";

    /**
     * Amounts travel as strings of minor units, so JavaScript clients do not round them (ADR-0003). At most 18
     * digits always fit in a {@code long}.
     */
    static final String AMOUNT_PATTERN = "[1-9][0-9]{0,17}";

    static final String AMOUNT_MESSAGE = "must be a positive integer of minor units with at most 18 digits";

    private ApiFormats() {}

    static String amount(Money money) {
        return Long.toString(money.amount());
    }

    static String currency(Money money) {
        return money.currency().getCurrencyCode();
    }
}
