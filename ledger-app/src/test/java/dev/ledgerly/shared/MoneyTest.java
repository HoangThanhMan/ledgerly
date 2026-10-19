package dev.ledgerly.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void plusAddsAmountsOfTheSameCurrency() {
        assertThat(Money.of(100, VND).plus(Money.of(50, VND))).isEqualTo(Money.of(150, VND));
    }

    @Test
    void minusSubtractsAmountsOfTheSameCurrency() {
        assertThat(Money.of(100, VND).minus(Money.of(150, VND))).isEqualTo(Money.of(-50, VND));
    }

    @Test
    void plusThrowsOnOverflowInsteadOfWrappingAround() {
        Money max = Money.of(Long.MAX_VALUE, VND);

        assertThatThrownBy(() -> max.plus(Money.of(1, VND))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void minusThrowsOnOverflowInsteadOfWrappingAround() {
        Money min = Money.of(Long.MIN_VALUE, VND);

        assertThatThrownBy(() -> min.minus(Money.of(1, VND))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateThrowsForTheSmallestLong() {
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, VND).negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(Money.of(100, VND).negate()).isEqualTo(Money.of(-100, VND));
    }

    @Test
    void plusRejectsADifferentCurrency() {
        assertThatThrownBy(() -> Money.of(100, VND).plus(Money.of(1, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VND")
                .hasMessageContaining("USD");
    }

    @Test
    void isLessThanRejectsADifferentCurrency() {
        assertThatThrownBy(() -> Money.of(100, VND).isLessThan(Money.of(1, USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isLessThanComparesAmounts() {
        assertThat(Money.of(-1, VND).isLessThan(Money.zero(VND))).isTrue();
        assertThat(Money.zero(VND).isLessThan(Money.zero(VND))).isFalse();
    }

    @Test
    void signPredicatesFollowTheAmount() {
        assertThat(Money.of(-1, VND).isNegative()).isTrue();
        assertThat(Money.zero(VND).isZero()).isTrue();
        assertThat(Money.of(1, VND).isPositive()).isTrue();
    }

    @Test
    void parsePositiveReadsMinorUnits() {
        assertThat(Money.parsePositive("150000", VND)).isEqualTo(Money.of(150_000, VND));
        assertThat(Money.parsePositive("9223372036854775807", VND)).isEqualTo(Money.of(Long.MAX_VALUE, VND));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "+1", "01", "1.5", "1e3", " 1", "1 ", "1_000", "٣"})
    void parsePositiveRejectsAnythingButAPositiveIntegerWithoutLeadingZeros(String text) {
        assertThatThrownBy(() -> Money.parsePositive(text, VND)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void parsePositiveRejectsAmountsAboveLongRange() {
        assertThatThrownBy(() -> Money.parsePositive("9223372036854775808", VND))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void toStringShowsAmountAndCurrency() {
        assertThat(Money.of(150_000, VND)).hasToString("150000 VND");
    }
}
