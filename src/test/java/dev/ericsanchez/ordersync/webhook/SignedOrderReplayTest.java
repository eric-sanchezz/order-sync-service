package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * The HMAC covers the body only; X-Shop-Event-Id is not signed. Anyone holding one signed order body (a log, a proxy,
 * the shop re-sending with a new delivery id) can post it again under a new event id. The ERP must not end up with
 * two orders for the same shop order.
 */
class SignedOrderReplayTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	// Red now (design finding for the architect): dedup is by event id only and the Idempotency-Key is the event id,
	// so the same signed body under a new event id is a second ERP order. Passes when the shop order id also
	// deduplicates (e.g. Idempotency-Key or a UNIQUE derived from "SHOP-<id>").
	@Test
	void sameSignedOrderUnderANewEventIdDoesNotCreateASecondErpOrder() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-1\"}")));

		postSignedOrder("evt-original", ORDER);
		postSignedOrder("evt-replayed-with-new-id", ORDER);
		this.worker.pollOnce();

		long erpOrdersWithDistinctKeys = this.erp.find(postRequestedFor(urlEqualTo(SALES_ORDERS)))
			.stream()
			.map((request) -> request.getHeader("Idempotency-Key"))
			.distinct()
			.count();
		assertThat(erpOrdersWithDistinctKeys).isEqualTo(1);
	}

}
