package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ERP answered, but storing that answer fails (the value does not fit the column). The job must still reach a
 * terminal or counted state; it must never sit in IN_PROGRESS and be re-sent to the ERP on every lease expiry.
 */
class ResultWriteFailureTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private JsonMapper json;

	// Breaks if: erp_order_id gets a length limit again without a fallback (the job would stay IN_PROGRESS and be
	// re-sent to the ERP after every lease).
	@Test
	void erpOrderIdLongerThanTheColumnDoesNotLoopForever() {
		String hugeOrderId = "ERP-" + "9".repeat(150);
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"orderId\":\"" + hugeOrderId + "\"}")));
		long jobId = this.json.readTree(postSignedOrder("evt-long-order-id", ORDER).getBody()).get("jobId").asLong();

		this.worker.pollOnce();
		// Simulate the 60 s lease running out, then let the worker look again.
		this.jdbc.sql("UPDATE sync_job SET locked_until = now() - interval '1 second' WHERE status = 'IN_PROGRESS'")
			.update();
		this.worker.pollOnce();

		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
		assertThat(jobRow(jobId).get("status")).isNotEqualTo("IN_PROGRESS");
	}

	// Breaks if: last_error stops replacing U+0000 and the fallback is removed (Postgres rejects 0x00 in text).
	@Test
	void erpErrorBodyWithANulByteStillSendsTheJobDead() {
		byte[] bodyWithNul = { '{', '"', 'e', '"', ':', '"', 'x', 0x00, 'y', '"', '}' };
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(422)
			.withBody(bodyWithNul)));
		long jobId = this.json.readTree(postSignedOrder("evt-nul-error", ORDER).getBody()).get("jobId").asLong();

		this.worker.pollOnce();
		this.jdbc.sql("UPDATE sync_job SET locked_until = now() - interval '1 second' WHERE status = 'IN_PROGRESS'")
			.update();
		this.worker.pollOnce();

		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
		assertThat(jobRow(jobId)).containsEntry("status", "DEAD").containsEntry("attempts", 1);
	}

}
