package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * A redelivered webhook is counted exactly once, under "duplicate", and never also as "accepted". The counters are
 * shared by the whole test context, so the test reads deltas.
 */
class WebhookCounterPerOutcomeTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the duplicate branch also counts "accepted" (or is not counted), or a delivery is counted twice
	// (e.g. once in the controller and once in a filter/advice).
	@Test
	void redeliveryOfTheSameEventCountsOneDuplicateAndNoAcceptance() {
		postSignedOrder("evt-count", ORDER);
		double acceptedBefore = metric("ordersync_webhook_received_total", "source=\"shop\"", "result=\"accepted\"");
		double duplicateBefore = metric("ordersync_webhook_received_total", "source=\"shop\"",
				"result=\"duplicate\"");

		postSignedOrder("evt-count", ORDER);

		assertThat(metric("ordersync_webhook_received_total", "source=\"shop\"", "result=\"accepted\""))
			.isEqualTo(acceptedBefore);
		assertThat(metric("ordersync_webhook_received_total", "source=\"shop\"", "result=\"duplicate\""))
			.isEqualTo(duplicateBefore + 1);
	}

}
