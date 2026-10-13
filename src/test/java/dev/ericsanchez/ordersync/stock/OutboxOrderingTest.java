package dev.ericsanchez.ordersync.stock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import dev.ericsanchez.ordersync.IntegrationTest;
import dev.ericsanchez.ordersync.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/** Head-of-line order per key: a message waits while an older one of the same SKU is still in flight. */
class OutboxOrderingTest extends IntegrationTest {

	private static final String SKU_A = "/wp-json/wc/v3/products/by-sku/SKU-A/stock";

	private static final String SKU_B = "/wp-json/wc/v3/products/by-sku/SKU-B/stock";

	@Autowired
	private OutboxRepository outbox;

	// Breaks if: the claim drops its NOT EXISTS (Y would be sent while X is in flight, and X could land last with the
	// old quantity), or the NOT EXISTS stops matching on aggregate_id (Z, another SKU, would be blocked by X).
	@Test
	void newerMessageWaitsForTheInFlightOneOfItsKeyButOtherKeysDoNot() {
		this.shop.register(put(urlPathMatching("/wp-json/wc/v3/products/by-sku/.+/stock"))
			.willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-x", """
				{"articleCode":"SKU-A","quantityAvailable":10,"changedAt":"2026-10-13T10:05:00Z"}""");
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		assertThat(this.outbox.claim(now, now.plusSeconds(60), 10)).hasSize(1);

		postSignedStockUpdate("erp-y", """
				{"articleCode":"SKU-A","quantityAvailable":20,"changedAt":"2026-10-13T10:06:00Z"}""");
		postSignedStockUpdate("erp-z", """
				{"articleCode":"SKU-B","quantityAvailable":30,"changedAt":"2026-10-13T10:06:00Z"}""");
		this.relay.pollOnce();

		assertThat(outboxRow(2)).containsEntry("status", "PENDING");
		assertThat(outboxRow(3)).containsEntry("status", "DELIVERED");
		this.shop.verifyThat(0, putRequestedFor(urlEqualTo(SKU_A)));

		// The relay that held X dies: its lease expires, X is picked up again and found older than the stored level.
		this.jdbc.sql("UPDATE outbox_message SET locked_until = now() - interval '1 second' WHERE id = 1").update();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(2)).containsEntry("status", "DELIVERED");
		});
		assertThat(outboxRow(1)).containsEntry("status", "SUPERSEDED");
		this.shop.verifyThat(1, putRequestedFor(urlEqualTo(SKU_A)));
		this.shop.verifyThat(1, putRequestedFor(urlEqualTo(SKU_A)).withRequestBody(equalToJson("{\"stock_quantity\":20}")));
		this.shop.verifyThat(1, putRequestedFor(urlEqualTo(SKU_B)));
	}

}
