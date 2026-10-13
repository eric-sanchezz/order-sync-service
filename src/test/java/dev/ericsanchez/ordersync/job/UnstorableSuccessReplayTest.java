package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ERP created the order with an id longer than the old varchar(100). It used to go DEAD, and a replay went DEAD
 * again because an idempotent ERP answers with the same id. Now any id the ERP returns can be stored.
 * (Adapted by the coder in H1 round 3: the original first asserted DEAD and then DONE after a replay, which cannot
 * hold once the success is stored on the first call.)
 */
class UnstorableSuccessReplayTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private JsonMapper json;

	// Breaks if: erp_order_id gets a length limit again (V3 migration reverted): the write fails, the job goes DEAD
	// and no replay can bring it to DONE.
	@Test
	void erpOrderIdOfAnyLengthIsStoredAndTheJobEndsDoneOnTheFirstCall() {
		String longOrderId = "ERP-" + "7".repeat(150);
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"orderId\":\"" + longOrderId + "\"}")));
		long jobId = this.json.readTree(postSignedOrder("evt-unstorable", ORDER).getBody()).get("jobId").asLong();

		this.worker.pollOnce();

		assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("erp_order_id", longOrderId);
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS))
			.withHeader("Idempotency-Key", equalTo("evt-unstorable")));
	}

}
