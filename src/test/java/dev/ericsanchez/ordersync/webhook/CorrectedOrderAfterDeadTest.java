package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Order 1001 arrives with an unmapped customer (999) and goes DEAD. The shop fixes the order and sends it again,
 * same order id, new event id, customer 42. The operator replays the DEAD job. The ERP must end up with the
 * CORRECTED order; with the order-id dedup the correction is swallowed as a "duplicate" and the replay re-sends the
 * original payload.
 */
class CorrectedOrderAfterDeadTest extends IntegrationTest {

	private static final String ORDER_FOR_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": %d },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	// Red now (regression of the V5 business key): the corrected delivery answers 200 duplicate, its payload is never
	// used, and the replay goes DEAD again with "unknown customer: 999". Passes when a newer delivery of a DEAD (not
	// yet synced) order can replace its payload, or is otherwise not dropped.
	@Test
	void correctedRedeliveryOfADeadOrderReachesTheErp() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-FIXED\"}")));
		postSignedOrder("evt-wrong-customer", ORDER_FOR_CUSTOMER.formatted(999));
		this.worker.pollOnce();
		assertThat(jobRow(1)).containsEntry("status", "DEAD");

		postSignedOrder("evt-corrected", ORDER_FOR_CUSTOMER.formatted(42));
		this.http.post().uri("/admin/jobs/1/replay").header("X-API-Key", ADMIN_KEY).retrieve().toBodilessEntity();
		this.worker.pollOnce();
		this.worker.pollOnce();

		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
		assertThat(this.jdbc.sql("SELECT count(*) FROM sync_job WHERE status = 'DONE'").query(Long.class).single())
			.isEqualTo(1);
	}

}
