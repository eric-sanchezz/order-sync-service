package dev.ericsanchez.ordersync.stock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Stock webhook inputs at the edges: timestamps, quantities and SKUs that end up in a URL. */
class StockEdgeCasesTest extends IntegrationTest {

	private static String stockUpdate(String articleCode, String quantity, String changedAt) {
		return """
				{"articleCode":"%s","quantityAvailable":%s,"warehouse":"BCN","changedAt":"%s"}
				""".formatted(articleCode, quantity, changedAt);
	}

	// Red now: the plan only calls an update stale when its changedAt is EARLIER than the stored one; the coder also
	// treats an EQUAL changedAt as stale. ERPs often stamp to the second, so a second change in the same second
	// (120 -> 80) is dropped and the shop keeps 120 forever. Passes when an equal changedAt is applied.
	@Test
	void secondChangeStampedWithTheSameSecondStillReachesTheShop() {
		this.shop.register(put(anyUrl()).willReturn(aResponse().withStatus(200)));

		postSignedStockUpdate("erp-same-1", stockUpdate("TORN-M6-100", "120", "2026-10-13T10:05:00Z"));
		ResponseEntity<String> second = postSignedStockUpdate("erp-same-2",
				stockUpdate("TORN-M6-100", "80", "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();
		this.relay.pollOnce();

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(this.jdbc.sql("SELECT quantity FROM stock_level WHERE article_code = 'TORN-M6-100'")
			.query(Long.class)
			.single()).isEqualTo(80);
	}

	// Red now: one event stamped years ahead (ERP clock bug, wrong offset) is accepted and from then on every real
	// update of that SKU is "stale": the shop's stock is frozen until that date. Passes when changedAt far in the
	// future is rejected (400) or otherwise cannot freeze the SKU.
	@Test
	void eventStampedFarInTheFutureDoesNotFreezeTheSku() {
		this.shop.register(put(anyUrl()).willReturn(aResponse().withStatus(200)));
		String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

		postSignedStockUpdate("erp-future", stockUpdate("TORN-M6-100", "999", "2099-01-01T00:00:00Z"));
		ResponseEntity<String> realUpdate = postSignedStockUpdate("erp-now", stockUpdate("TORN-M6-100", "7", now));
		this.relay.pollOnce();
		this.relay.pollOnce();

		assertThat(realUpdate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(this.jdbc.sql("SELECT quantity FROM stock_level WHERE article_code = 'TORN-M6-100'")
			.query(Long.class)
			.single()).isEqualTo(7);
	}

	// Breaks if: the SKU is concatenated into the path instead of expanded as an encoded URI variable (a "/" or "?"
	// in a SKU would hit another shop endpoint or drop part of the path).
	@Test
	void skuWithUrlSpecialCharactersIsSentAsOneEncodedPathSegment() {
		this.shop.register(put(anyUrl()).willReturn(aResponse().withStatus(200)));

		postSignedStockUpdate("erp-sku", stockUpdate("A/B?c#d e", "5", "2026-10-13T10:05:00Z"));
		this.relay.pollOnce();

		this.shop.verifyThat(1, anyRequestedFor(anyUrl()));
		this.shop.verifyThat(1, putRequestedFor(urlEqualTo("/wp-json/wc/v3/products/by-sku/A%2FB%3Fc%23d%20e/stock"))
			.withRequestBody(equalToJson("{\"stock_quantity\":5}")));
	}

	// Breaks if: quantityAvailable is read as a type that silently wraps or truncates (a number beyond long must be
	// rejected, never stored as a different value).
	@Test
	void quantityBeyondLongIsABadRequestAndNothingIsStored() {
		ResponseEntity<String> response = postSignedStockUpdate("erp-huge",
				stockUpdate("TORN-M6-100", "99999999999999999999", "2026-10-13T10:05:00Z"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("erp_inbox_event")).isZero();
		assertThat(countRows("stock_level")).isZero();
		assertThat(countRows("outbox_message")).isZero();
	}

}
