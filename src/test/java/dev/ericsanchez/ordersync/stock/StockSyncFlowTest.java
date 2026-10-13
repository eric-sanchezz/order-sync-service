package dev.ericsanchez.ordersync.stock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** ERP stock webhook -> stock_level + outbox -> relay -> shop, against real Postgres and the WireMock shop. */
class StockSyncFlowTest extends IntegrationTest {

	private static final String ANY_STOCK_PUT = "/wp-json/wc/v3/products/by-sku/.+/stock";

	// Breaks if: the dispatch guard compares changedAt instead of message order (both changes carry the same second,
	// so the replayed 120 would be sent and overwrite the newer 80).
	@Test
	void replayOfAnOldDeadMessageWithTheSameChangedAtAsTheNewerOneIsSuperseded() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":120}"))
			.willReturn(aResponse().withStatus(400)));
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":80}"))
			.willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-same-a", stockUpdate("TORN-M6-100", 120, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();
		postSignedStockUpdate("erp-same-b", stockUpdate("TORN-M6-100", 80, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();

		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).atPriority(1).willReturn(aResponse().withStatus(200)));
		this.http.post().uri("/admin/outbox/{id}/replay", 1).header("X-API-Key", ADMIN_KEY).retrieve().toBodilessEntity();
		this.relay.pollOnce();

		assertThat(outboxRow(1)).containsEntry("status", "SUPERSEDED");
		assertThat(outboxRow(2)).containsEntry("status", "DELIVERED");
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":120}")));
	}

	private static String stockUpdate(String articleCode, long quantity, String changedAt) {
		return """
				{"articleCode":"%s","quantityAvailable":%d,"warehouse":"BCN","changedAt":"%s"}
				""".formatted(articleCode, quantity, changedAt);
	}

	// Breaks if: the shop path, the stock_quantity body, the Idempotency-Key or the bearer token are mapped wrongly.
	@Test
	void validStockUpdateReachesTheShopAndIsDelivered() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));

		ResponseEntity<String> response = postSignedStockUpdate("erp-s1",
				stockUpdate("TORN-M6-100", 120, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isEqualTo("{\"outboxId\":1,\"status\":\"PENDING\"}");
		assertThat(outboxRow(1)).containsEntry("status", "DELIVERED").containsEntry("attempts", 1);
		assertThat(this.jdbc.sql("SELECT quantity FROM stock_level WHERE article_code = 'TORN-M6-100'")
			.query(Long.class)
			.single()).isEqualTo(120);
		this.shop.verifyThat(1, putRequestedFor(urlEqualTo("/wp-json/wc/v3/products/by-sku/TORN-M6-100/stock"))
			.withHeader("Idempotency-Key", equalTo("outbox-1"))
			.withHeader("Authorization", equalTo("Bearer test-shop-token"))
			.withRequestBody(equalToJson("{\"stock_quantity\":120}")));
	}

	// Breaks if: the ERP inbox insert loses its ON CONFLICT (the redelivery would fail or enqueue a second PUT).
	@Test
	void sameStockEventDeliveredTwiceSendsOnePut() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));
		String body = stockUpdate("TORN-M6-100", 120, "2026-10-13T10:05:00Z");

		ResponseEntity<String> first = postSignedStockUpdate("erp-s3", body);
		ResponseEntity<String> second = postSignedStockUpdate("erp-s3", body);
		this.relay.pollOnce();

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody()).isEqualTo("{\"duplicate\":true}");
		assertThat(countRows("outbox_message")).isOne();
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT)));
	}

	// Breaks if: the webhook stops marking older unsent messages SUPERSEDED (message 1 would still be PENDING
	// before the relay runs; the dispatch-time guard alone would hide this from a PUT count).
	@Test
	void secondChangeBeforeTheRelayRunsSupersedesTheFirst() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));

		postSignedStockUpdate("erp-s4-a", stockUpdate("TORN-M6-100", 100, "2026-10-13T10:05:00Z"));
		postSignedStockUpdate("erp-s4-b", stockUpdate("TORN-M6-100", 80, "2026-10-13T10:06:00Z"));

		assertThat(outboxRow(1)).containsEntry("status", "SUPERSEDED");
		this.relay.pollOnce();
		assertThat(outboxRow(2)).containsEntry("status", "DELIVERED");
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT)));
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":80}")));
	}

	// Breaks if: the upsert drops "WHERE stock_level.changed_at < EXCLUDED.changed_at" (the old quantity would win).
	@Test
	void olderChangeThanTheStoredOneIsStaleAndChangesNothing() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-s5-new", stockUpdate("TORN-M6-100", 80, "2026-10-13T10:06:00Z"));
		this.relay.pollOnce();

		ResponseEntity<String> stale = postSignedStockUpdate("erp-s5-old",
				stockUpdate("TORN-M6-100", 100, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();

		assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(stale.getBody()).isEqualTo("{\"stale\":true}");
		assertThat(this.jdbc.sql("SELECT quantity FROM stock_level WHERE article_code = 'TORN-M6-100'")
			.query(Long.class)
			.single()).isEqualTo(80);
		assertThat(countRows("erp_inbox_event")).isEqualTo(2);
		assertThat(countRows("outbox_message")).isOne();
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT)));
	}

	// Breaks if: a 5xx from the shop is classified as permanent (the message would go DEAD after one call).
	@Test
	void shopServerErrorIsRetriedUntilDelivered() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).inScenario("flaky")
			.whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("recovered"));
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).inScenario("flaky")
			.whenScenarioStateIs("recovered")
			.willReturn(aResponse().withStatus(200)));

		postSignedStockUpdate("erp-s6", stockUpdate("TORN-M6-100", 120, "2026-10-13T10:05:00Z"));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(1)).containsEntry("status", "DELIVERED").containsEntry("attempts", 2);
		});
		this.shop.verifyThat(2, putRequestedFor(urlPathMatching(ANY_STOCK_PUT)));
	}

	// Breaks if: a 4xx from the shop is classified as retryable, or the outbox replay does not reset the message.
	@Test
	void rejectedStockUpdateGoesDeadAndCanBeReplayedOnce() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT))
			.willReturn(aResponse().withStatus(400).withBody("{\"message\":\"unknown sku\"}")));
		postSignedStockUpdate("erp-s7", stockUpdate("TORN-M6-100", 120, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();
		assertThat(outboxRow(1)).containsEntry("status", "DEAD")
			.containsEntry("attempts", 1)
			.containsEntry("last_error", "HTTP 400: {\"message\":\"unknown sku\"}");

		this.shop.resetMappings();
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));
		ResponseEntity<String> replay = this.http.post()
			.uri("/admin/outbox/{id}/replay", 1)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);
		this.relay.pollOnce();

		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(outboxRow(1)).containsEntry("status", "DELIVERED");
		ResponseEntity<String> secondReplay = this.http.post()
			.uri("/admin/outbox/{id}/replay", 1)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);
		assertThat(secondReplay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	// Breaks if: the dispatcher stops checking for a newer message of the SKU before the PUT (the replayed old
	// quantity 100 would overwrite the newer 80 in the shop).
	@Test
	void replayOfAnOldDeadMessageAfterANewerDeliveryIsSuperseded() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":100}"))
			.willReturn(aResponse().withStatus(400)));
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":80}"))
			.willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-s9-a", stockUpdate("TORN-M6-100", 100, "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();
		postSignedStockUpdate("erp-s9-b", stockUpdate("TORN-M6-100", 80, "2026-10-13T10:06:00Z"));
		this.relay.pollOnce();
		assertThat(outboxRow(1)).containsEntry("status", "DEAD");
		assertThat(outboxRow(2)).containsEntry("status", "DELIVERED");

		// The shop would now accept quantity 100 (higher priority than the 400 stub; the journal is kept).
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).atPriority(1).willReturn(aResponse().withStatus(200)));
		this.http.post().uri("/admin/outbox/{id}/replay", 1).header("X-API-Key", ADMIN_KEY).retrieve().toBodilessEntity();
		this.relay.pollOnce();

		assertThat(outboxRow(1)).containsEntry("status", "SUPERSEDED");
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":100}")));
	}

}
