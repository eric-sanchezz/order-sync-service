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
 * The ERP keeps accepting the order, but writing DONE fails EVERY time with an error that is not a data-integrity
 * rejection (SQLSTATE P0001, raised by a trigger only this test installs; think of a broken deploy). The worker
 * leaves the job to its lease each time, so the ERP calls must stop at max-attempts (5) and the job must end DEAD.
 */
class PersistentNonIntegrityWriteErrorTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@AfterEach
	void dropTrigger() {
		this.jdbc.sql("DROP TRIGGER IF EXISTS fail_done ON sync_job").update();
		this.jdbc.sql("DROP FUNCTION IF EXISTS fail_done()").update();
	}

	// Breaks if: re-claiming an expired lease stops adding an attempt, or the "attempts >= max -> DEAD without
	// calling" check at the start of process() is removed (the ERP would be called on every lease, forever).
	@Test
	void doneWriteThatAlwaysFailsStopsCallingTheErpAtMaxAttemptsAndEndsDead() {
		this.jdbc.sql("""
				CREATE FUNCTION fail_done() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'broken deploy'; END $$ LANGUAGE plpgsql
				""").update();
		this.jdbc.sql("""
				CREATE TRIGGER fail_done BEFORE UPDATE ON sync_job
				FOR EACH ROW WHEN (NEW.status = 'DONE') EXECUTE FUNCTION fail_done()
				""").update();
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-P\"}")));
		postSignedOrder("evt-broken-deploy", ORDER);

		for (int lease = 0; lease < 8; lease++) {
			this.worker.pollOnce();
			this.jdbc.sql("UPDATE sync_job SET locked_until = now() - interval '1 second'"
					+ " WHERE status = 'IN_PROGRESS'").update();
		}

		this.erp.verifyThat(5, postRequestedFor(urlEqualTo(SALES_ORDERS)));
		assertThat(jobRow(1)).containsEntry("status", "DEAD").containsEntry("attempts", 5);
		assertThat((String) jobRow(1).get("last_error")).startsWith("gave up after 5 attempts");
	}

}
