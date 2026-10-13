package dev.ericsanchez.ordersync.transform;

import java.util.List;

/**
 * Sales order in the ERP's format: amounts in cents, ERP customer and article codes. It is the output of
 * {@link OrderTransformer}, so it lives here and the ERP client depends on it (not the other way round).
 */
public record ErpOrder(
		String customerCode,
		String externalReference,
		String currency,
		long totalAmountCents,
		List<Line> lines) {

	public record Line(int lineNumber, String articleCode, int quantity, long unitPriceCents, long lineAmountCents) {
	}

}
