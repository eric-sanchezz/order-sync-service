package dev.ericsanchez.ordersync.events;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * Order 1001 fails (unknown customer) while Kafka is down, so its OrderFailed event uses up its attempts and goes
 * DEAD. Kafka comes back, the shop sends the corrected order, it reaches the ERP and OrderSynced is published. The
 * operator then does what ADR 0006 says and replays the DEAD event. A consumer reading the key in offset order must
 * end on the order's real state (synced), not on a failure that happened before it.
 */
class KafkaOutageReordersEventsTest extends KafkaIntegrationTest {

	private static final String ORDER_FOR_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": %d },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Red now: a DEAD KAFKA message does not block its key, so OrderSynced overtakes it and the replay publishes
	// OrderFailed last, with nothing in the record (no per-key sequence) telling the consumer it is stale. Passes when
	// a DEAD event blocks its key for KAFKA, KAFKA events are not sent DEAD by a short outage, or similar.
	// Adapted by the coder in H6 round 2: with "KAFKA events are not sent DEAD by a short outage" in place, the event
	// can no longer reach DEAD during the pause, so step 2 now waits until it has failed more times than the attempt
	// budget (5), which is where it used to go DEAD, and is still RETRY. The replay below then answers 409 (not DEAD)
	// and changes nothing; the final assertion is unchanged.
	@Test
	void replayingAnEventThatWentDeadDuringAnOutageDoesNotLeaveTheConsumerOnAStaleState() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-1\"}")));
		postSignedOrder("evt-wrong-customer", ORDER_FOR_CUSTOMER.formatted(999));
		this.worker.pollOnce();

		this.kafka.getDockerClient().pauseContainerCmd(this.kafka.getContainerId()).exec();
		try {
			await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
				this.relay.pollOnce();
				assertThat(this.jdbc.sql("SELECT status, attempts FROM outbox_message WHERE event_type = 'OrderFailed'")
					.query().singleRow()).containsEntry("status", "RETRY")
					.hasEntrySatisfying("attempts", (attempts) -> assertThat((Integer) attempts).isGreaterThan(5));
			});
		}
		finally {
			this.kafka.getDockerClient().unpauseContainerCmd(this.kafka.getContainerId()).exec();
		}

		postSignedOrder("evt-corrected", ORDER_FOR_CUSTOMER.formatted(42));
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			this.worker.pollOnce();
			this.relay.pollOnce();
			assertThat(this.jdbc.sql("SELECT status FROM outbox_message WHERE event_type = 'OrderSynced'")
				.query(String.class).single()).isEqualTo("DELIVERED");
		});
		long deadEvent = this.jdbc.sql("SELECT id FROM outbox_message WHERE event_type = 'OrderFailed'")
			.query(Long.class).single();
		this.http.post().uri("/admin/outbox/{id}/replay", deadEvent).header("X-API-Key", ADMIN_KEY).retrieve()
			.toBodilessEntity();
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(deadEvent)).containsEntry("status", "DELIVERED");
		});

		List<ConsumerRecord<String, String>> records = consume("ordersync.orders.v1",
				List.of(eventIdsInOutbox("OrderFailed").get(0), eventIdsInOutbox("OrderSynced").get(0)));
		records.sort(Comparator.comparingLong(ConsumerRecord::offset));
		assertThat(jobRow(1)).containsEntry("status", "DONE");
		assertThat(header(records.get(records.size() - 1), "eventType")).isEqualTo("OrderSynced");
	}

}
