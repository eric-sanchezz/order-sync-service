package dev.ericsanchez.ordersync.transform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;

import dev.ericsanchez.ordersync.webhook.ShopOrder;
import dev.ericsanchez.ordersync.webhook.ShopOrder.Customer;
import dev.ericsanchez.ordersync.webhook.ShopOrder.LineItem;
import org.junit.jupiter.api.Test;

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
