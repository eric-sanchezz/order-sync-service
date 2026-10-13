package dev.ericsanchez.ordersync.transform;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the shop's decimal strings ({@code "12.50"}) into cents. No double, no BigDecimal: digits only, at most two
 * decimals, no sign and no exponent. Twelve integer digits keep the result far below {@link Long#MAX_VALUE}.
 */
public final class MoneyParser {

	private static final Pattern AMOUNT = Pattern.compile("^(\\d{1,12})(?:\\.(\\d{1,2}))?$");

	private MoneyParser() {
	}

	public static OptionalLong toCents(String amount) {
		if (amount == null) {
			return OptionalLong.empty();
		}
		Matcher matcher = AMOUNT.matcher(amount);
		if (!matcher.matches()) {
			return OptionalLong.empty();
		}
		long units = Long.parseLong(matcher.group(1));
		String decimals = (matcher.group(2) != null) ? matcher.group(2) : "";
		long cents = switch (decimals.length()) {
			case 0 -> 0;
			case 1 -> Long.parseLong(decimals) * 10;
			default -> Long.parseLong(decimals);
		};
		return OptionalLong.of(units * 100 + cents);
	}

}
