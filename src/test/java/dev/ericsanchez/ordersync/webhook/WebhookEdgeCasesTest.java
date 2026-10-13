package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Boundaries of the webhook contract that the plan's T2/T11 do not cover. */
class WebhookEdgeCasesTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the length check becomes ">= 200" (or "< 200"): an event id of exactly 200 characters is valid.
	@Test
	void eventIdOfExactlyTwoHundredCharactersIsAccepted() {
		ResponseEntity<String> response = postSignedOrder("e".repeat(200), ORDER);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(countRows("inbox_event")).isEqualTo(1);
	}

	// Breaks if: accept-float-as-int is enabled again (1.9 becomes 1, matches the total and the job goes DONE).
	@Test
	void fractionalQuantityIsNotSilentlyTruncated() {
		String order = """
				{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
				  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1.9, "price": "12.50" } ] }
				""";

		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)));

		ResponseEntity<String> response = postSignedOrder("evt-fraction", order);
		this.worker.pollOnce();

		if (response.getStatusCode() == HttpStatus.ACCEPTED) {
			assertThat(jobRow(1).get("status")).isEqualTo("DEAD");
		}
		else {
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

}
