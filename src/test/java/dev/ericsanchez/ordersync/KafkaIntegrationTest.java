package dev.ericsanchez.ordersync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.kafka.KafkaContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared infrastructure for the Kafka tests: their own Spring context, with domain events enabled and a real broker.
 * Topics are not emptied between tests, so tests find their events by the event ids stored in the outbox. The
 * worker's lease is short here so that a lease takeover can be played out in real time.
 */
@Import(KafkaTestcontainersConfiguration.class)
@TestPropertySource(properties = { "ordersync.events.kafka.enabled=true", "ordersync.worker.lease=200ms" })
public abstract class KafkaIntegrationTest extends IntegrationTest {

	@Autowired
	protected KafkaContainer kafka;

	/** Event ids of the KAFKA outbox messages of {@code eventType}, oldest first. */
	protected List<String> eventIdsInOutbox(String eventType) {
		return this.jdbc.sql("""
				SELECT payload::jsonb ->> 'eventId' FROM outbox_message
				WHERE destination = 'KAFKA' AND event_type = :eventType ORDER BY id
				""")
			.param("eventType", eventType)
			.query(String.class)
			.list();
	}

	/**
	 * Reads {@code topic} from the beginning, as a new consumer group, until every one of {@code eventIds} has been
	 * seen (header {@code eventId}); returns those records in the order they were read.
	 */
	protected List<ConsumerRecord<String, String>> consume(String topic, List<String> eventIds) {
		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafka.getBootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(topic));
			await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
					if (eventIds.contains(header(record, "eventId"))) {
						found.add(record);
					}
				}
				assertThat(found.stream().map((record) -> header(record, "eventId"))).containsAll(eventIds);
			});
		}
		return found;
	}

	protected static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header != null) ? new String(header.value(), StandardCharsets.UTF_8) : null;
	}

}
