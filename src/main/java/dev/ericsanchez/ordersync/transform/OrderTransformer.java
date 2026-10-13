package dev.ericsanchez.ordersync.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongFunction;

import dev.ericsanchez.ordersync.webhook.ShopOrder;

/**
 * Shop order -> ERP order. Pure: the customer lookup comes in as a function, and every problem is returned as
 * {@link Invalid} (the job goes DEAD with that reason) instead of being thrown. Amounts use exact long arithmetic, so
 * an overflow is reported as invalid rather than wrapping around.
 */
public final class OrderTransformer {

	private static final String SUPPORTED_CURRENCY = "EUR";

	private OrderTransformer() {
	}

	public sealed interface Result permits Ok, Invalid {
	}

	public record Ok(ErpOrder order) implements Result {
	}

	public record Invalid(String reason) implements Result {
	}

	public static Result transform(ShopOrder order, LongFunction<Optional<String>> erpCustomerCode) {
		if (order == null) {
			return new Invalid("order is empty");
		}
		if (order.id() == null) {
			return new Invalid("missing field: id");
		}
		if (order.currency() == null) {
			return new Invalid("missing field: currency");
		}
		if (!SUPPORTED_CURRENCY.equals(order.currency())) {
			return new Invalid("unsupported currency: " + order.currency());
		}
		OptionalLong total = MoneyParser.toCents(order.total());
		if (total.isEmpty()) {
			return new Invalid("missing or invalid field: total");
		}
		if (order.customer() == null || order.customer().id() == null) {
			return new Invalid("missing field: customer.id");
		}
		if (order.lineItems() == null || order.lineItems().isEmpty()) {
			return new Invalid("line_items must not be empty");
		}

		List<ErpOrder.Line> lines = new ArrayList<>();
		long sumOfLines = 0;
		try {
			for (int i = 0; i < order.lineItems().size(); i++) {
				ShopOrder.LineItem item = order.lineItems().get(i);
				int lineNumber = i + 1;
				if (item == null || item.sku() == null || item.sku().isBlank()) {
					return new Invalid("line " + lineNumber + ": missing sku");
				}
				if (item.quantity() == null || item.quantity() < 1) {
					return new Invalid("line " + lineNumber + ": quantity must be at least 1");
				}
				OptionalLong unitPrice = MoneyParser.toCents(item.price());
				if (unitPrice.isEmpty()) {
					return new Invalid("line " + lineNumber + ": missing or invalid price");
				}
				long lineAmount = Math.multiplyExact(item.quantity(), unitPrice.getAsLong());
				sumOfLines = Math.addExact(sumOfLines, lineAmount);
				lines.add(new ErpOrder.Line(lineNumber, item.sku(), item.quantity(), unitPrice.getAsLong(), lineAmount));
			}
		}
		catch (ArithmeticException overflow) {
			return new Invalid("amount overflow");
		}
		if (sumOfLines != total.getAsLong()) {
			return new Invalid("sum of lines (" + sumOfLines + " cents) does not match total (" + total.getAsLong()
					+ " cents)");
		}

		long shopCustomerId = order.customer().id();
		Optional<String> customerCode = erpCustomerCode.apply(shopCustomerId);
		if (customerCode.isEmpty()) {
			return new Invalid("unknown customer: " + shopCustomerId);
		}
		return new Ok(new ErpOrder(customerCode.get(), "SHOP-" + order.id(), SUPPORTED_CURRENCY, total.getAsLong(),
				List.copyOf(lines)));
	}

}
