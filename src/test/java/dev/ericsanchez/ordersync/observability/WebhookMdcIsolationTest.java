package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/**
 * Tomcat reuses request threads and the MDC is per thread. After a webhook that carried event id "evt-leak", later
 * requests without any event id must not log it: a leaked id would point an operator at the wrong order.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookMdcIsolationTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the webhook stops removing eventId from the MDC in a finally block (the next request served by the
	// same Tomcat thread would log the previous order's event id). 40 follow-up requests cover the default pool.
	@Test
	void eventIdOfOneRequestNeverShowsUpInTheLogsOfLaterRequests(CapturedOutput output) {
		postSignedOrder("evt-leak", ORDER);

		for (int i = 0; i < 40; i++) {
			this.http.post()
				.uri("/webhooks/shop/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.body(ORDER)
				.retrieve()
				.toBodilessEntity();
		}

		assertThat(output.getOut().lines().filter((line) -> line.contains("Rejected shop webhook")))
			.hasSize(40)
			.allSatisfy((line) -> assertThat(line).contains("[eventId= "));
	}

}
