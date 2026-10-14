package dev.ericsanchez.ordersync.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import dev.ericsanchez.ordersync.webhook.ShopOrder;
import dev.ericsanchez.ordersync.webhook.ShopOrder.Customer;
import dev.ericsanchez.ordersync.webhook.ShopOrder.LineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTransformerTest {

	private static final LongFunction<Optional<String>> CUSTOMERS = (id) -> (id == 42) ? Optional.of("CLI-0042")
			: Optional.empty();

	// Breaks if: any field is mapped wrongly (unit price, line amount, line numbers, codes or reference).
	@Test
	void mapsAValidOrderToTheErpFormat() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "150.00", new Customer(42L, "a@b.test", "Ferreteria"),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 10, "12.50"),
						new LineItem("LLAVE-ALLEN-5", "Llave", 5, "5.00")));

		OrderTransformer.Result result = OrderTransformer.transform(order, CUSTOMERS);

		assertThat(result).isEqualTo(new OrderTransformer.Ok(new ErpOrder("CLI-0042", "SHOP-1001", "EUR", 15000,
				List.of(new ErpOrder.Line(1, "TORN-M6-100", 10, 1250, 12500),
						new ErpOrder.Line(2, "LLAVE-ALLEN-5", 5, 500, 2500)))));
	}

	// Breaks if: the check becomes quantity <= 1 (a single unit is a valid line).
	@Test
	void acceptsAQuantityOfOne() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "12.50", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS)).isEqualTo(new OrderTransformer.Ok(new ErpOrder(
				"CLI-0042", "SHOP-1001", "EUR", 1250, List.of(new ErpOrder.Line(1, "TORN-M6-100", 1, 1250, 1250)))));
	}

	// Each row is one validation of its own, with the reason the job goes DEAD with.
	// Breaks if: any of these checks is removed (the order would reach the ERP, or a NullPointerException would
	// send the job to RETRY forever) or returns another reason.
	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidOrders")
	void rejectsInvalidOrdersWithTheirReason(String description, ShopOrder order, String reason) {
		assertThat(OrderTransformer.transform(order, CUSTOMERS)).isEqualTo(new OrderTransformer.Invalid(reason));
	}

	static Stream<Arguments> invalidOrders() {
		Customer customer = new Customer(42L, null, null);
		List<LineItem> oneLine = List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.50"));
		return Stream.of(
				Arguments.of("no order at all", null, "order is empty"),
				Arguments.of("no currency", new ShopOrder(1001L, "1001", null, "12.50", customer, oneLine),
						"missing field: currency"),
				Arguments.of("malformed total", new ShopOrder(1001L, "1001", "EUR", "12,50", customer, oneLine),
						"missing or invalid field: total"),
				Arguments.of("blank sku", new ShopOrder(1001L, "1001", "EUR", "12.50", customer,
						List.of(new LineItem(" ", "Tornillo", 1, "12.50"))), "line 1: missing sku"),
				Arguments.of("malformed price", new ShopOrder(1001L, "1001", "EUR", "12.50", customer,
						List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.5.0"))),
						"line 1: missing or invalid price"),
				Arguments.of("customer the ERP does not know",
						new ShopOrder(1001L, "1001", "EUR", "12.50", new Customer(999L, null, null), oneLine),
						"unknown customer: 999"));
	}

	// Breaks if: a missing required field is not checked (the ERP would get "SHOP-null").
	@Test
	void rejectsAnOrderWithoutId() {
		ShopOrder order = new ShopOrder(null, "1001", "EUR", "12.50", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("missing field: id"));
	}

	// Breaks if: the sum of quantity x price is not compared with the total.
	@Test
	void rejectsLinesThatDoNotAddUpToTheTotal() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "100.00", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 10, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("sum of lines (12500 cents) does not match total (10000 cents)"));
	}

	// Breaks if: the currency check is dropped.
	@Test
	void rejectsCurrenciesOtherThanEur() {
		ShopOrder order = new ShopOrder(1001L, "1001", "USD", "12.50", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("unsupported currency: USD"));
	}

	// Breaks if: the check becomes quantity < 0 (a zero-quantity line would reach the ERP).
	@Test
	void rejectsZeroQuantity() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "0.00", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 0, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("line 1: quantity must be at least 1"));
	}

	// Breaks if: plain * replaces Math.multiplyExact (the amount would silently wrap around).
	@Test
	void reportsAmountOverflowInsteadOfWrapping() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "1.00", new Customer(42L, null, null),
				List.of(new LineItem("TORN-M6-100", "Tornillo", 2_000_000_000, "999999999999.99")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("amount overflow"));
	}

	// Breaks if: customer is dereferenced without a null check (NPE -> the job would loop in RETRY).
	@Test
	void rejectsAnOrderWithoutCustomer() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "12.50", null,
				List.of(new LineItem("TORN-M6-100", "Tornillo", 1, "12.50")));

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("missing field: customer.id"));
	}

	// Breaks if: an empty line list is accepted (the ERP would get an order with no lines and total 0).
	@Test
	void rejectsAnOrderWithoutLines() {
		ShopOrder order = new ShopOrder(1001L, "1001", "EUR", "0.00", new Customer(42L, null, null), List.of());

		assertThat(OrderTransformer.transform(order, CUSTOMERS))
			.isEqualTo(new OrderTransformer.Invalid("line_items must not be empty"));
	}

}
