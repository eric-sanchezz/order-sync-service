package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The same shop order delivered again under a new event id: what happens depends on the state of its job. */
class OrderRedeliveryTest extends IntegrationTest {

	private static final String ORDER_FOR_CUSTOMER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": %d },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the waiting job keeps its first payload (the ERP would get CLI-0042), or the redelivery resets the
	// attempts it has already spent (attempts would be 0).
	@Test
	void redeliveryOfAJobWaitingForARetryReplacesItsPayloadAndKeepsItsAttempts() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)));
		postSignedOrder("evt-first", ORDER_FOR_CUSTOMER.formatted(42));
		this.jdbc.sql("UPDATE sync_job SET status = 'RETRY', attempts = 2, next_attempt_at = now() + interval '1 hour'")
			.update();

		ResponseEntity<String> redelivery = postSignedOrder("evt-second", ORDER_FOR_CUSTOMER.formatted(77));

		assertThat(redelivery.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(redelivery.getBody()).isEqualTo("{\"jobId\":1,\"status\":\"PENDING\"}");
		assertThat(jobRow(1)).containsEntry("status", "RETRY").containsEntry("attempts", 2);
		this.jdbc.sql("UPDATE sync_job SET next_attempt_at = now() - interval '1 minute'").update();
		this.worker.pollOnce();
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS))
			.withRequestBody(equalToJson("{\"customerCode\":\"CLI-0077\"}", true, true)));
	}

	// Breaks if: a DEAD job is treated as a duplicate (the corrected order would never be sent) or keeps its
	// attempts and error instead of starting over.
	@Test
	void redeliveryOfADeadJobStartsItOverWithTheNewPayload() {
		postSignedOrder("evt-unknown-customer", ORDER_FOR_CUSTOMER.formatted(999));
		this.worker.pollOnce();
		assertThat(jobRow(1)).containsEntry("status", "DEAD");

		ResponseEntity<String> corrected = postSignedOrder("evt-corrected", ORDER_FOR_CUSTOMER.formatted(42));

		assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(jobRow(1)).containsEntry("status", "PENDING")
			.containsEntry("attempts", 0)
			.containsEntry("last_error", null)
			.containsEntry("completed_at", null);
	}

	// Breaks if: the refused delivery is kept in the inbox (the shop's retry of the same event would then be a
	// "duplicate" and the correction would be lost), or an in-flight job is changed under the worker's feet.
	@Test
	void redeliveryWhileTheJobIsInFlightIsRefusedAndCanBeRetriedLater() {
		postSignedOrder("evt-first", ORDER_FOR_CUSTOMER.formatted(42));
		this.jdbc.sql("UPDATE sync_job SET status = 'IN_PROGRESS', locked_until = now() + interval '1 minute'")
			.update();

		ResponseEntity<String> refused = postSignedOrder("evt-correction", ORDER_FOR_CUSTOMER.formatted(77));

		assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countRows("inbox_event")).isOne();
		assertThat(jobRow(1)).containsEntry("status", "IN_PROGRESS");

		this.jdbc.sql("UPDATE sync_job SET status = 'RETRY', locked_until = NULL").update();
		ResponseEntity<String> retried = postSignedOrder("evt-correction", ORDER_FOR_CUSTOMER.formatted(77));
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	}

	// Breaks if: a DONE job takes the new payload or is reset (the order would be created in the ERP again).
	@Test
	void redeliveryOfAnOrderAlreadyInTheErpIsADuplicate() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-1\"}")));
		postSignedOrder("evt-first", ORDER_FOR_CUSTOMER.formatted(42));
		this.worker.pollOnce();

		ResponseEntity<String> redelivery = postSignedOrder("evt-update", ORDER_FOR_CUSTOMER.formatted(77));
		this.worker.pollOnce();

		assertThat(redelivery.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(redelivery.getBody()).isEqualTo("{\"jobId\":1,\"duplicate\":true}");
		assertThat(jobRow(1)).containsEntry("status", "DONE").containsEntry("erp_order_id", "ERP-1");
		assertThat(countRows("sync_job")).isOne();
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

}
