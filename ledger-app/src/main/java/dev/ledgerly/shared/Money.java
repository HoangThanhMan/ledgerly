package dev.ledgerly.shared;

import java.util.Currency;
import java.util.regex.Pattern;

/**
 * An amount in the minor unit of its currency (for VND, one dong).
 *
 * <p>Arithmetic never wraps around: overflow throws {@link ArithmeticException}. Mixing currencies is a programming
 * error and throws {@link IllegalArgumentException}.
 */
public record Money(long amount, Currency currency) {

    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");

    public static Money of(long amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * Parses an API amount: a positive integer in minor units, ASCII digits only, without sign or leading zeros.
     *
     * @throws NumberFormatException if the text has any other shape or does not fit in a {@code long}
     */
    public static Money parsePositive(String text, Currency currency) {
        if (!POSITIVE_INTEGER.matcher(text).matches()) {
            throw new NumberFormatException("amount must be a positive integer in minor units: \"" + text + "\"");
        }
        return new Money(Long.parseLong(text), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amount, other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amount, other.amount), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amount), currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount < other.amount;
    }

    public boolean isNegative() {
        return amount < 0;
    }

    public boolean isZero() {
        return amount == 0;
    }

    public boolean isPositive() {
        return amount > 0;
    }

    @Override
    public String toString() {
        return amount + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: " + this + " and " + other);
        }
    }
}
