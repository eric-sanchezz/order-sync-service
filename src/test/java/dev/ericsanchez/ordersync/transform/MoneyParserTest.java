package dev.ericsanchez.ordersync.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyParserTest {

	// Breaks if: one decimal digit is not scaled ("0.1" would give 1 cent) or units are not multiplied by 100.
	@ParameterizedTest
	@CsvSource({ "12.50, 1250", "12, 1200", "0.1, 10" })
	void validDecimalStringsBecomeCents(String amount, long expectedCents) {
		assertThat(MoneyParser.toCents(amount)).isEqualTo(OptionalLong.of(expectedCents));
	}

	// Breaks if: the regex accepts a sign, more than two decimals, an exponent, letters or the empty string, or a
	// missing amount (null) is not answered with empty.
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "12.345", "-1", "abc", "", "1e3" })
	void malformedAmountsAreRejected(String amount) {
		assertThat(MoneyParser.toCents(amount)).isEmpty();
	}

}
