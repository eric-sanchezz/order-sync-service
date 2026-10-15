package dev.ericsanchez.ordersync.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.TestPropertySource;

/**
 * Tester, H6 round 2. Kafka really rejects the event (every record is larger than the producer's max.request.size
 * here): retrying cannot help, so the event must go DEAD instead of being retried forever.
 */
@TestPropertySource(properties = "spring.kafka.producer.properties.max.request.size=200")
class KafkaRejectedEventTest extends KafkaIntegrationTest {

	private static final String ORDER_UNKNOWN_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": 999 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: RecordTooLargeException (or the walk down the cause chain) is no longer treated as permanent: with
	// retriesUntilDelivered the event would stay in RETRY for ever and its key would never move.
	@Test
	void anEventKafkaRejectsGoesDeadAtTheFirstAttempt() {
		postSignedOrder("evt-too-large", ORDER_UNKNOWN_CUSTOMER);
		this.worker.pollOnce();
		assertThat(jobRow(1)).containsEntry("status", "DEAD");

		this.relay.pollOnce();
		this.relay.pollOnce();

		assertThat(outboxRow(1)).containsEntry("status", "DEAD").containsEntry("attempts", 1);
		assertThat((String) outboxRow(1).get("last_error")).startsWith("Kafka rejected the event");
	}

}
