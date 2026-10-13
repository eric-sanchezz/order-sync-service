package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.ericsanchez.ordersync.IntegrationTest;
import dev.ericsanchez.ordersync.erp.ErpClient;
import dev.ericsanchez.ordersync.erp.ErpResult;
import dev.ericsanchez.ordersync.transform.ErpOrder;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Worker A claims a job, its lease runs out while it is still talking to the ERP, and the real worker re-claims and
 * sends the order again. Both sends must carry the same Idempotency-Key (so the ERP can drop the second one) and A's
 * late success must not be stored on top of the job the second worker owns.
 */
class SlowWorkerLeaseTakeoverTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private SyncJobRepository jobs;

	@Autowired
	private ErpClient erpClient;

	// Breaks if: the Idempotency-Key stops being the event id (a lease takeover would create a second ERP order),
	// or the result guard loses the locked_until check (A's late write would win).
	@Test
	void secondSendAfterALeaseTakeoverReusesTheIdempotencyKeyAndOnlyOneResultIsStored() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-SAME\"}")));
		postSignedOrder("evt-takeover", ORDER);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		ClaimedJob claimedBySlowWorker = this.jobs.claim(now, now.minusSeconds(1), 5).get(0);

		this.worker.pollOnce();
		ErpResult lateResult = this.erpClient.createSalesOrder(claimedBySlowWorker.eventId(),
				new ErpOrder("CLI-0042", "SHOP-1001", "EUR", 1250,
						List.of(new ErpOrder.Line(1, "TORN-M6-100", 1, 1250, 1250))));
		boolean lateWriteStored = this.jobs.markDone(claimedBySlowWorker, 1, "ERP-LATE", now);

		assertThat(lateResult).isInstanceOf(ErpResult.Success.class);
		assertThat(lateWriteStored).isFalse();
		this.erp.verifyThat(2, postRequestedFor(urlEqualTo(SALES_ORDERS))
			.withHeader("Idempotency-Key", equalTo("evt-takeover")));
		assertThat(jobRow(claimedBySlowWorker.id())).containsEntry("status", "DONE")
			.containsEntry("erp_order_id", "ERP-SAME");
	}

}
