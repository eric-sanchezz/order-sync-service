package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ERP answers the headers quickly but drips the body for 8 s; the test timeout is 1 s. The worker must give up on
 * that call within the timeout, not hang (a hung pollOnce blocks the scheduler and outlives the lease).
 */
class ErpSlowBodyTimeoutTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private JsonMapper json;

	// Breaks if: the ERP read timeout only covers the response headers, or is removed from the request factory.
	@Test
	void bodyDrippedSlowerThanTheTimeoutEndsAsRetryWithinTheTimeout() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-SLOW-0000000000000000000000000000000000\"}")
			.withChunkedDribbleDelay(8, 8000)));
		long jobId = this.json.readTree(postSignedOrder("evt-slow-body", ORDER).getBody()).get("jobId").asLong();

		CompletableFuture<Void> poll = CompletableFuture.runAsync(this.worker::pollOnce);

		await().atMost(Duration.ofSeconds(4)).until(poll::isDone);
		assertThat(jobRow(jobId)).containsEntry("status", "RETRY").containsEntry("attempts", 1);
		assertThat((String) jobRow(jobId).get("last_error")).startsWith("IO:");
	}

}
