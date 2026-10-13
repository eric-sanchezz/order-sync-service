package dev.ericsanchez.ordersync.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class AdminOutboxApiTest extends IntegrationTest {

	@Autowired
	private JsonMapper json;

	// Breaks if: the status filter is ignored (the DELIVERED message would also be listed as DEAD).
	@Test
	void listFiltersByStatus() {
		this.shop.register(put(urlPathMatching(".*/SKU-OK/stock")).willReturn(aResponse().withStatus(200)));
		this.shop.register(put(urlPathMatching(".*/SKU-BAD/stock")).willReturn(aResponse().withStatus(404)));
		postSignedStockUpdate("erp-ok", """
				{"articleCode":"SKU-OK","quantityAvailable":1,"changedAt":"2026-10-13T10:05:00Z"}""");
		postSignedStockUpdate("erp-bad", """
				{"articleCode":"SKU-BAD","quantityAvailable":1,"changedAt":"2026-10-13T10:05:00Z"}""");
		this.relay.pollOnce();

		JsonNode dead = this.json.readTree(this.http.get()
			.uri("/admin/outbox?status=DEAD")
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.body(String.class));

		assertThat(dead.get("totalElements").asLong()).isOne();
		assertThat(dead.get("items").get(0).get("aggregateId").asString()).isEqualTo("SKU-BAD");
		assertThat(dead.get("items").get(0).get("lastError").asString()).startsWith("HTTP 404");
	}

	// Breaks if: an unknown status or a missing message is answered with 200 or 500 instead of a client error.
	@ParameterizedTest(name = "{0} {1} -> {2}")
	@CsvSource({ "GET, /admin/outbox?status=FOO, 400", "GET, /admin/outbox/999, 404",
			"POST, /admin/outbox/999/replay, 404" })
	void badRequestsAreClientErrors(String method, String path, int expectedStatus) {
		int actualStatus = this.http.method(HttpMethod.valueOf(method))
			.uri(path)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toBodilessEntity()
			.getStatusCode()
			.value();

		assertThat(actualStatus).isEqualTo(expectedStatus);
	}

}
