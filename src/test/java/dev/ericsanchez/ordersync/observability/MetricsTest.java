package dev.ericsanchez.ordersync.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import dev.ericsanchez.ordersync.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * What /actuator/prometheus (management port) reports. The Spring context, and with it every counter and timer, is
 * shared by all test classes and never reset, so counters and timers are checked as a difference before/after.
 * The gauges come from the database (truncated before each test) through a snapshot refreshed in the background, so
 * they are checked as absolute values once the snapshot has caught up.
 */
class MetricsTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	@Qualifier("shopCircuitBreaker")
	private CircuitBreaker shopCircuitBreaker;

	// Breaks if: ErpClient stops recording the ordersync.erp.requests timer (or tags it wrongly), or the job gauge
	// stops reading sync_job.
	@Test
	void successfulSyncIsCountedOnceAndShowsUpInTheJobGauge() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)));
		double successesBefore = metric("ordersync_erp_requests_seconds_count", "outcome=\"success\"");

		postSignedOrder("evt-o1", ORDER);
		this.worker.pollOnce();

		assertThat(metric("ordersync_erp_requests_seconds_count", "outcome=\"success\"") - successesBefore)
			.isEqualTo(1.0);
		// The job gauges come from a database snapshot refreshed in the background (every 100 ms in tests).
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(metric("ordersync_jobs", "status=\"DONE\"")).isEqualTo(1.0);
			assertThat(metric("ordersync_jobs", "status=\"PENDING\"")).isZero();
		});
	}

	// Breaks if: the age gauge takes max(created_at) (the newest job, about 0 s) instead of min (the oldest).
	@Test
	void ageOfTheOldestPendingJobIsReported() {
		postSignedOrder("evt-new", ORDER);
		this.jdbc.sql("INSERT INTO inbox_event (event_id, payload, received_at) VALUES ('evt-old', '{}', now())")
			.update();
		this.jdbc.sql("""
				INSERT INTO sync_job (inbox_event_id, event_id, status, attempts, next_attempt_at, created_at, updated_at)
				SELECT id, 'evt-old', 'RETRY', 1, now() + interval '1 hour', now() - interval '120 seconds', now()
				FROM inbox_event WHERE event_id = 'evt-old'
				""").update();

		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_jobs_oldest_pending_age_seconds")).isGreaterThanOrEqualTo(120.0));
	}

	// Breaks if: the webhook stops counting rejected deliveries, or tags them with another source or result.
	@Test
	void rejectedWebhookIsCountedBySourceAndResult() {
		double unauthorizedBefore = metric("ordersync_webhook_received_total", "source=\"shop\"",
				"result=\"unauthorized\"");

		this.http.post().uri("/webhooks/shop/orders").header("X-Shop-Event-Id", "evt-no-signature").body(ORDER)
			.retrieve().toBodilessEntity();

		assertThat(metric("ordersync_webhook_received_total", "source=\"shop\"", "result=\"unauthorized\"")
				- unauthorizedBefore).isEqualTo(1.0);
	}

	// Breaks if: a breaker is built outside the shared CircuitBreakerRegistry bean: the Micrometer binding (done by
	// spring-cloud-circuitbreaker-resilience4j for that registry) would not see it and its state series would be missing.
	@Test
	void circuitBreakerStateIsExported() {
		this.shopCircuitBreaker.transitionToForcedOpenState();

		assertThat(metric("resilience4j_circuitbreaker_state", "name=\"shop\"", "state=\"forced_open\"")).isEqualTo(1.0);
		assertThat(metric("resilience4j_circuitbreaker_state", "name=\"erp\"", "state=\"closed\"")).isEqualTo(1.0);
	}

}
