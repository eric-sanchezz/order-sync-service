package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The ERP accepted the order but Postgres rejects the DONE row as data (SQLSTATE 23514, raised by a trigger that only
 * this test installs, since no real value triggers it any more). That cannot succeed on a later attempt, so the job
 * must leave IN_PROGRESS for DEAD instead of being re-sent every time its lease expires.
 */
class ResultWriteFallbackTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@AfterEach
	void dropTrigger() {
		this.jdbc.sql("DROP TRIGGER IF EXISTS reject_done ON sync_job").update();
		this.jdbc.sql("DROP FUNCTION IF EXISTS reject_done()").update();
	}

	// Breaks if: the DataIntegrityViolationException fallback is removed (the job stays IN_PROGRESS and the next
	// claim after the lease sends the order to the ERP a second time).
	@Test
	void resultRejectedAsDataSendsTheJobDeadAfterOneErpCall() {
		this.jdbc.sql("""
				CREATE FUNCTION reject_done() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'rejected by test' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
				""").update();
		this.jdbc.sql("""
				CREATE TRIGGER reject_done BEFORE UPDATE ON sync_job
				FOR EACH ROW WHEN (NEW.status = 'DONE') EXECUTE FUNCTION reject_done()
				""").update();
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-F\"}")));
		postSignedOrder("evt-rejected-result", ORDER);

		this.worker.pollOnce();
		this.jdbc.sql("UPDATE sync_job SET locked_until = now() - interval '1 second' WHERE status = 'IN_PROGRESS'")
			.update();
		this.worker.pollOnce();

		assertThat(jobRow(1)).containsEntry("status", "DEAD").containsEntry("attempts", 1);
		assertThat((String) jobRow(1).get("last_error"))
			.startsWith("result could not be stored (the ERP accepted the order)");
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

}
