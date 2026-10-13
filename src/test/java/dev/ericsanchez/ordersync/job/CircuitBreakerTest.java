package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import dev.ericsanchez.ordersync.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

class CircuitBreakerTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	// Breaks if: a call refused by the open circuit consumes an attempt (attempts would be 1).
	@Test
	void openCircuitPostponesTheJobWithoutUsingAnAttempt() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-9\"}")));
		CircuitBreaker erpBreaker = this.circuitBreakers.circuitBreaker("erp");
		erpBreaker.transitionToForcedOpenState();
		postSignedOrder("evt-t9", ORDER);

		this.worker.pollOnce();

		assertThat(jobRow(1)).containsEntry("status", "RETRY").containsEntry("attempts", 0);
		this.erp.verifyThat(0, postRequestedFor(urlEqualTo(SALES_ORDERS)));

		erpBreaker.reset();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(1)).containsEntry("status", "DONE").containsEntry("attempts", 1);
		});
	}

}
