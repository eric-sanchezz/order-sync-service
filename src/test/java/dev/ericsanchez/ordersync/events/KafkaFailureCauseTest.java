package dev.ericsanchez.ordersync.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.TestPropertySource;

/**
 * What the operator reads in {@code last_error} when Kafka refuses an event. Same context as the tester's
 * KafkaRejectedEventTest: every record is larger than the producer's max.request.size.
 */
@TestPropertySource(properties = "spring.kafka.producer.properties.max.request.size=200")
class KafkaFailureCauseTest extends KafkaIntegrationTest {

	private static final String ORDER_UNKNOWN_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": 999 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// /admin/outbox/{id} only shows last_error, and the replay is meant for "once the cause is fixed".
	// Breaks if: last_error stores spring-kafka's wrapper ("KafkaException: Send failed") instead of the cause Kafka
	// gave, so a record too large could not be told apart from an invalid topic or a missing authorization.
	@Test
	void lastErrorOfARejectedEventNamesTheRootCause() {
		postSignedOrder("evt-cause", ORDER_UNKNOWN_CUSTOMER);
		this.worker.pollOnce();

		this.relay.pollOnce();

		assertThat(outboxRow(1)).containsEntry("status", "DEAD");
		assertThat((String) outboxRow(1).get("last_error"))
			.startsWith("Kafka rejected the event: org.apache.kafka.common.errors.RecordTooLargeException");
	}

}
