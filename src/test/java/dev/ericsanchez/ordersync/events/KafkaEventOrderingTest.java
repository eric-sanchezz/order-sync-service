package dev.ericsanchez.ordersync.events;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What keeps the events of one aggregate in order when Kafka misbehaves: the kafka circuit breaker, a DEAD event
 * holding back its key, and the per-aggregate sequence.
 */
class KafkaEventOrderingTest extends KafkaIntegrationTest {

	private static final String ORDER_FOR_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": %d },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	@Qualifier("kafkaCircuitBreaker")
	private CircuitBreaker kafkaBreaker;

	@Autowired
	private DomainEvents events;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JsonMapper json;

	// Breaks if: a call the open breaker refused counts as an attempt (or is treated as a failure to retry with
	// backoff): Kafka was never called, so the event must keep its whole budget.
	@Test
	void openKafkaBreakerPostponesTheEventWithoutUsingAnAttempt() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-1\"}")));
		postSignedOrder("evt-breaker", ORDER_FOR_CUSTOMER.formatted(42));
		this.worker.pollOnce();
		this.kafkaBreaker.transitionToForcedOpenState();

		this.relay.pollOnce();

		assertThat(outboxRow(1)).containsEntry("status", "RETRY")
			.containsEntry("attempts", 0)
			.containsEntry("last_error", "circuit breaker open");
		this.kafkaBreaker.reset();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(1)).containsEntry("status", "DELIVERED").containsEntry("attempts", 1);
		});
		assertThat(consume("ordersync.orders.v1", eventIdsInOutbox("OrderSynced"))).hasSize(1);
	}

	// The flow the tester found, with an event that is DEAD for a reason a retry cannot fix (forced here; a short
	// outage no longer sends events DEAD). Breaks if: a DEAD KAFKA event stops blocking its key (OrderSynced would be
	// published first and the replayed OrderFailed would end up last on the topic).
	@Test
	void deadEventHoldsBackItsKeyUntilItIsReplayed() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-2\"}")));
		postSignedOrder("evt-unknown-customer", ORDER_FOR_CUSTOMER.formatted(999));
		this.worker.pollOnce();
		this.jdbc.sql("UPDATE outbox_message SET status = 'DEAD', last_error = 'forced by the test' WHERE id = 1")
			.update();
		postSignedOrder("evt-corrected", ORDER_FOR_CUSTOMER.formatted(42));
		this.worker.pollOnce();
		assertThat(jobRow(1)).containsEntry("status", "DONE");

		this.relay.pollOnce();
		this.relay.pollOnce();

		assertThat(outboxRow(2)).containsEntry("status", "PENDING");
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_events_blocked_keys")).isEqualTo(1.0));
		this.http.post().uri("/admin/outbox/{id}/replay", 1).header("X-API-Key", ADMIN_KEY).retrieve()
			.toBodilessEntity();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(2)).containsEntry("status", "DELIVERED");
		});
		List<ConsumerRecord<String, String>> records = consume("ordersync.orders.v1",
				List.of(eventIdsInOutbox("OrderFailed").get(0), eventIdsInOutbox("OrderSynced").get(0)));
		assertThat(records).extracting((record) -> header(record, "eventType"))
			.containsExactly("OrderFailed", "OrderSynced");
		assertThat(records).extracting((record) -> this.json.readTree(record.value()).get("sequence").asLong())
			.containsExactly(1L, 2L);
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_events_blocked_keys")).isEqualTo(0.0));
	}

	// Order 1001 and the SKU "1001" are two aggregates, so two blocked keys. Breaks if: the gauge counts DEAD events
	// by aggregate_id only (it would report 1 while two keys are held back).
	@Test
	void blockedKeysCountsAnOrderAndASkuWithTheSameIdAsTwoKeys() {
		postSignedOrder("evt-order-1001", ORDER_FOR_CUSTOMER.formatted(999));
		this.worker.pollOnce();
		postSignedStockUpdate("erp-sku-1001", """
				{"articleCode":"1001","quantityAvailable":3,"warehouse":"BCN","changedAt":"2026-10-13T10:00:00Z"}
				""");

		this.jdbc.sql("UPDATE outbox_message SET status = 'DEAD', last_error = 'forced by the test' "
				+ "WHERE destination = 'KAFKA'")
			.update();

		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_events_blocked_keys")).isEqualTo(2.0));
	}

	// Four transactions write an event of the same SKU at once; each then waits up to 500 ms for the others before
	// committing. Breaks if: the next sequence is read without locking the aggregate (e.g. max + 1): all four would
	// read the same value while they wait for each other and get the same number.
	@Test
	void sequenceFollowsTheCommitOrderWhenWritersRace() throws Exception {
		CyclicBarrier allWritten = new CyclicBarrier(4);
		TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		List<Future<?>> writers = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			for (int i = 0; i < 4; i++) {
				long quantity = i;
				writers.add(executor.submit(() -> transactions.executeWithoutResult((status) -> {
					this.events.stockUpdated("RACE-SKU", quantity, "BCN", Instant.parse("2026-10-13T10:00:00Z"),
							"race-" + quantity, now);
					waitForTheOthers(allWritten);
				})));
			}
			for (Future<?> writer : writers) {
				writer.get();
			}
		}

		List<Long> sequencesInOutboxOrder = this.jdbc.sql("""
				SELECT (payload::jsonb ->> 'sequence')::bigint FROM outbox_message
				WHERE aggregate_id = 'RACE-SKU' ORDER BY id
				""").query(Long.class).list();
		assertThat(sequencesInOutboxOrder).containsExactly(1L, 2L, 3L, 4L);
	}

	/** With a per-aggregate lock only one writer reaches this point at a time, so the wait times out; that is fine. */
	private static void waitForTheOthers(CyclicBarrier barrier) {
		try {
			barrier.await(500, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException | BrokenBarrierException ex) {
			// expected when the writers are serialised
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
