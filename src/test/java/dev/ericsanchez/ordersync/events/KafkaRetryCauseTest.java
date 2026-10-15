package dev.ericsanchez.ordersync.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * What the operator reads in {@code last_error} while the broker is away (H6 round 3): the RETRY branch of the Kafka
 * dispatcher, with a real paused broker.
 */
class KafkaRetryCauseTest extends KafkaIntegrationTest {

	private static final String ORDER_UNKNOWN_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": 999 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the retryable branch stores spring-kafka's wrapper ("KafkaException: Send failed") or the exception
	// returned by get() instead of the innermost cause, so the operator cannot tell a timeout from anything else.
	@Test
	void lastErrorOfAnEventRetriedWhileTheBrokerIsPausedNamesTheKafkaCause() {
		postSignedOrder("evt-retry-cause", ORDER_UNKNOWN_CUSTOMER);
		this.worker.pollOnce();

		this.kafka.getDockerClient().pauseContainerCmd(this.kafka.getContainerId()).exec();
		try {
			await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
				this.relay.pollOnce();
				assertThat(outboxRow(1)).containsEntry("status", "RETRY");
				assertThat(outboxRow(1).get("last_error")).isNotNull();
			});
		}
		finally {
			this.kafka.getDockerClient().unpauseContainerCmd(this.kafka.getContainerId()).exec();
		}

		String lastError = (String) outboxRow(1).get("last_error");
		assertThat(lastError).startsWith("Kafka send failed: org.apache.kafka.common.errors.TimeoutException")
			.doesNotContain("KafkaException");
	}

}
