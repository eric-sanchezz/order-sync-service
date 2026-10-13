package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Webhook -> job -> ERP, end to end against real Postgres and the WireMock ERP. */
class OrderSyncFlowTest extends IntegrationTest {

	private static final String ORDER_FROM_CUSTOMER_42 = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "150.00", "created_at": "2026-10-13T10:00:00Z",
			  "customer": { "id": 42, "email": "compras@ferreteria-ejemplo.test", "company": "Ferreteria Ejemplo SL" },
			  "line_items": [
			    { "sku": "TORN-M6-100", "name": "Tornillo M6 (caja 100)", "quantity": 10, "price": "12.50" },
			    { "sku": "LLAVE-ALLEN-5", "name": "Llave Allen 5mm", "quantity": 5, "price": "5.00" } ] }
			""";

	@Autowired
	private JsonMapper json;

	// Breaks if: the Idempotency-Key header is dropped, or unitPriceCents/lineAmountCents are mapped wrongly.
	@Test
	void validOrderReachesTheErpAndEndsDone() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"orderId\":\"ERP-000123\"}")));

		ResponseEntity<String> response = postSignedOrder("evt-t1", ORDER_FROM_CUSTOMER_42);
		long jobId = this.json.readTree(response.getBody()).get("jobId").asLong();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("erp_order_id", "ERP-000123");
		});
		assertThat(jobRow(jobId).get("completed_at")).isNotNull();
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS))
			.withHeader("Idempotency-Key", equalTo("evt-t1"))
			.withRequestBody(equalToJson("""
					{ "customerCode": "CLI-0042", "externalReference": "SHOP-1001", "currency": "EUR",
					  "totalAmountCents": 15000,
					  "lines": [
					    { "lineNumber": 1, "articleCode": "TORN-M6-100", "quantity": 10,
					      "unitPriceCents": 1250, "lineAmountCents": 12500 },
					    { "lineNumber": 2, "articleCode": "LLAVE-ALLEN-5", "quantity": 5,
					      "unitPriceCents": 500, "lineAmountCents": 2500 } ] }
					""")));
	}

	// Breaks if: a 5xx is classified as permanent (the job would go DEAD after the first call).
	@Test
	void serverErrorIsRetriedUntilTheErpAccepts() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).inScenario("flaky")
			.whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("recovered"));
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).inScenario("flaky")
			.whenScenarioStateIs("recovered")
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-2\"}")));

		long jobId = this.json.readTree(postSignedOrder("evt-t4", ORDER_FROM_CUSTOMER_42).getBody())
			.get("jobId")
			.asLong();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("attempts", 2);
		});
		this.erp.verifyThat(2, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	// Breaks if: a 4xx is classified as retryable (more than one call before DEAD), or replay is broken.
	@Test
	void rejectedOrderGoesDeadAndCanBeReplayedOnce() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(422).withBody("{\"error\":\"article blocked\"}")));
		long jobId = this.json.readTree(postSignedOrder("evt-t5", ORDER_FROM_CUSTOMER_42).getBody())
			.get("jobId")
			.asLong();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DEAD")
				.containsEntry("attempts", 1)
				.containsEntry("last_error", "HTTP 422: {\"error\":\"article blocked\"}");
		});

		this.erp.resetMappings();
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-5\"}")));
		ResponseEntity<String> replay = this.http.post()
			.uri("/admin/jobs/{id}/replay", jobId)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);

		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("erp_order_id", "ERP-5");
		});
		ResponseEntity<String> secondReplay = this.http.post()
			.uri("/admin/jobs/{id}/replay", jobId)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);
		assertThat(secondReplay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	// Breaks if: the give-up check is "attempts > max" instead of ">=" (six calls instead of five).
	@Test
	void erpThatAlwaysFailsSendsTheJobDeadAfterFiveAttempts() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(503)));

		long jobId = this.json.readTree(postSignedOrder("evt-t6", ORDER_FROM_CUSTOMER_42).getBody())
			.get("jobId")
			.asLong();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DEAD").containsEntry("attempts", 5);
		});
		this.worker.pollOnce();
		this.erp.verifyThat(5, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	// Breaks if: the customer lookup falls back to a default code instead of failing.
	@Test
	void unknownCustomerGoesDeadWithoutCallingTheErp() {
		String orderFromCustomer999 = ORDER_FROM_CUSTOMER_42.replace("\"id\": 42", "\"id\": 999");

		long jobId = this.json.readTree(postSignedOrder("evt-t10", orderFromCustomer999).getBody())
			.get("jobId")
			.asLong();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DEAD")
				.containsEntry("last_error", "unknown customer: 999");
		});
		this.erp.verifyThat(0, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	// Breaks if: the claim drops the "IN_PROGRESS with expired lease" branch (the job would stay stuck forever).
	@Test
	void jobLeftInProgressByACrashedWorkerIsClaimedAgain() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-12\"}")));
		long inboxEventId = this.jdbc.sql("""
				INSERT INTO inbox_event (event_id, payload, received_at) VALUES ('evt-t12', :payload, now())
				RETURNING id
				""").param("payload", ORDER_FROM_CUSTOMER_42).query(Long.class).single();
		long jobId = this.jdbc.sql("""
				INSERT INTO sync_job (inbox_event_id, event_id, status, attempts, next_attempt_at, locked_until,
				                      created_at, updated_at)
				VALUES (:inboxEventId, 'evt-t12', 'IN_PROGRESS', 0, now() - interval '2 minutes',
				        now() - interval '1 minute', now() - interval '2 minutes', now() - interval '2 minutes')
				RETURNING id
				""").param("inboxEventId", inboxEventId).query(Long.class).single();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("erp_order_id", "ERP-12");
		});
	}

	// Breaks if: re-claiming an expired lease stops counting an attempt (the job would be sent to the ERP again, and
	// a job that keeps crashing its worker would loop forever).
	@Test
	void jobWhoseLeaseExpiredOnItsLastAttemptGoesDeadWithoutAnotherErpCall() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-13\"}")));
		long inboxEventId = this.jdbc.sql("""
				INSERT INTO inbox_event (event_id, payload, received_at) VALUES ('evt-lost-results', :payload, now())
				RETURNING id
				""").param("payload", ORDER_FROM_CUSTOMER_42).query(Long.class).single();
		long jobId = this.jdbc.sql("""
				INSERT INTO sync_job (inbox_event_id, event_id, status, attempts, next_attempt_at, locked_until,
				                      created_at, updated_at)
				VALUES (:inboxEventId, 'evt-lost-results', 'IN_PROGRESS', 4, now() - interval '2 minutes',
				        now() - interval '1 minute', now() - interval '2 minutes', now() - interval '2 minutes')
				RETURNING id
				""").param("inboxEventId", inboxEventId).query(Long.class).single();

		this.worker.pollOnce();

		assertThat(jobRow(jobId)).containsEntry("status", "DEAD").containsEntry("attempts", 5);
		this.erp.verifyThat(0, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	// Breaks if: the error text is stored as is (Postgres rejects U+0000 in text) and only the generic "could not be
	// stored" fallback saves the job, losing the ERP's message.
	@Test
	void erpErrorBodyWithANulCharacterIsKeptReadableInLastError() {
		byte[] bodyWithNul = { '{', '"', 'e', '"', ':', '"', 'x', 0x00, 'y', '"', '}' };
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(422).withBody(bodyWithNul)));

		long jobId = this.json.readTree(postSignedOrder("evt-nul", ORDER_FROM_CUSTOMER_42).getBody())
			.get("jobId")
			.asLong();
		this.worker.pollOnce();

		assertThat(jobRow(jobId)).containsEntry("status", "DEAD")
			.containsEntry("last_error", "HTTP 422: {\"e\":\"x<NUL>y\"}");
	}

}
