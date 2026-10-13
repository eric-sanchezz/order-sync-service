package dev.ericsanchez.ordersync.stock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/** With the shop breaker open, a stock message is postponed without being sent and without spending an attempt. */
class ShopCircuitBreakerTest extends IntegrationTest {

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	private JsonMapper json;

	// Breaks if: CircuitOpen is recorded with attempts + 1, or the relay treats it as retryable/permanent, or the
	// dispatcher calls the shop outside the breaker.
	@Test
	void openShopBreakerPostponesWithoutSpendingAnAttemptAndDeliversAfterReset() {
		this.shop.register(put(anyUrl()).willReturn(aResponse().withStatus(200)));
		long outboxId = this.json.readTree(postSignedStockUpdate("erp-cb",
				"{\"articleCode\":\"TORN-M6-100\",\"quantityAvailable\":3,\"changedAt\":\"2026-10-13T10:05:00Z\"}")
			.getBody()).get("outboxId").asLong();
		this.circuitBreakers.circuitBreaker("shop").transitionToForcedOpenState();

		this.relay.pollOnce();

		assertThat(outboxRow(outboxId)).containsEntry("status", "RETRY")
			.containsEntry("attempts", 0)
			.containsEntry("last_error", "circuit breaker open");
		this.shop.verifyThat(0, putRequestedFor(anyUrl()));

		this.circuitBreakers.circuitBreaker("shop").reset();
		this.jdbc.sql("UPDATE outbox_message SET next_attempt_at = now() - interval '1 minute' WHERE id = :id").param("id", outboxId).update();
		this.relay.pollOnce();

		assertThat(outboxRow(outboxId)).containsEntry("status", "DELIVERED").containsEntry("attempts", 1);
		this.shop.verifyThat(1, putRequestedFor(anyUrl()));
	}

}
