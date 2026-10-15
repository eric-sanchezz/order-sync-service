package dev.ericsanchez.ordersync.events;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Domain events from the outbox to a real Kafka broker, read back with a plain consumer: content, key, order per key,
 * and what happens when Kafka or the outbox write fails.
 */
class DomainEventsToKafkaTest extends KafkaIntegrationTest {

	private static final String ORDER_1001 = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "150.00",
			  "customer": { "id": 42 },
			  "line_items": [
			    { "sku": "TORN-M6-100", "quantity": 10, "price": "12.50" },
			    { "sku": "LLAVE-ALLEN-5", "quantity": 5, "price": "5.00" } ] }
			""";

	private static final String ANY_STOCK_PUT = "/wp-json/wc/v3/products/by-sku/.+/stock";

	@Autowired
	private JsonMapper json;

	@AfterEach
	void cleanUp() {
		this.jdbc.sql("DROP TRIGGER IF EXISTS reject_kafka_event ON outbox_message").update();
		this.jdbc.sql("DROP FUNCTION IF EXISTS reject_kafka_event()").update();
	}

	// Breaks if: the DONE transition does not write OrderSynced to the outbox, the record key is not the shop order
	// id, or the eventType header is missing.
	@Test
	void orderSyncedReachesTheOrdersTopicKeyedByTheShopOrder() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"orderId\":\"ERP-777\"}")));
		postSignedOrder("evt-k1", ORDER_1001);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			this.relay.pollOnce();
			assertThat(this.jdbc.sql("SELECT status FROM outbox_message WHERE destination = 'KAFKA'")
				.query(String.class)
				.list()).containsExactly("DELIVERED");
		});
		List<String> eventIds = eventIdsInOutbox("OrderSynced");

		List<ConsumerRecord<String, String>> records = consume("ordersync.orders.v1", eventIds);

		ConsumerRecord<String, String> record = records.getFirst();
		assertThat(record.key()).isEqualTo("1001");
		assertThat(header(record, "eventType")).isEqualTo("OrderSynced");
		assertThat(header(record, "correlationId")).isEqualTo("evt-k1");
		JsonNode event = this.json.readTree(record.value());
		assertThat(event.get("eventId").asString()).isEqualTo(eventIds.getFirst());
		assertThat(event.get("type").asString()).isEqualTo("OrderSynced");
		assertThat(event.get("aggregateId").asString()).isEqualTo("1001");
		assertThat(event.get("version").asInt()).isEqualTo(1);
		assertThat(event.get("sequence").asLong()).isEqualTo(1);
		assertThat(event.get("occurredAt").asString()).isNotBlank();
		assertThat(event.get("payload")).isEqualTo(this.json.readTree("""
				{ "shopOrderId": 1001, "jobId": 1, "erpOrderId": "ERP-777", "totalAmountCents": 15000,
				  "currency": "EUR" }
				"""));
	}

	// Breaks if: the DEAD transition does not write OrderFailed, or the reason is not the job's last error.
	@Test
	void orderFailedCarriesTheErpRejection() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(422)
			.withBody("{\"error\":\"customer blocked\"}")));
		postSignedOrder("evt-k2", ORDER_1001);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(1)).containsEntry("status", "DEAD");
		});
		this.relay.pollOnce();

		List<ConsumerRecord<String, String>> records = consume("ordersync.orders.v1",
				eventIdsInOutbox("OrderFailed"));

		ConsumerRecord<String, String> record = records.getFirst();
		assertThat(record.key()).isEqualTo("1001");
		JsonNode payload = this.json.readTree(record.value()).get("payload");
		assertThat(payload.get("reason").asString()).contains("HTTP 422").contains("customer blocked");
		assertThat(payload.get("attempts").asInt()).isEqualTo(1);
		assertThat(payload.get("shopOrderId").asLong()).isEqualTo(1001);
	}

	// Breaks if: the SUPERSEDED rule for the shop's stock messages is applied to KAFKA too (the first StockUpdated
	// would never be published).
	@Test
	void bothStockChangesArePublishedWhileTheShopGetsOnlyTheNewest() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-k3-a", stockUpdate(10, "2026-10-13T10:05:00Z"));
		postSignedStockUpdate("erp-k3-b", stockUpdate(20, "2026-10-13T10:06:00Z"));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(this.jdbc.sql("SELECT status FROM outbox_message WHERE destination = 'KAFKA'")
				.query(String.class)
				.list()).containsExactly("DELIVERED", "DELIVERED");
		});
		List<ConsumerRecord<String, String>> records = consume("ordersync.stock.v1",
				eventIdsInOutbox("StockUpdated"));

		assertThat(records).extracting((record) -> this.json.readTree(record.value())
			.get("payload")
			.get("quantityAvailable")
			.asLong()).containsExactly(10L, 20L);
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT)));
		this.shop.verifyThat(1, putRequestedFor(urlPathMatching(ANY_STOCK_PUT))
			.withRequestBody(equalToJson("{\"stock_quantity\":20}")));
	}

	// Breaks if: the claim stops holding back a newer message of a key while an older one is in flight (both events
	// of the SKU would be sent in the same batch, in parallel, in no guaranteed order).
	@Test
	void eventsOfOneKeyArePublishedOneAfterTheOtherInOrder() {
		this.shop.register(put(urlPathMatching(ANY_STOCK_PUT)).willReturn(aResponse().withStatus(200)));
		postSignedStockUpdate("erp-k4-a", stockUpdate(30, "2026-10-13T11:00:00Z"));
		postSignedStockUpdate("erp-k4-b", stockUpdate(40, "2026-10-13T11:01:00Z"));
		List<String> eventIds = eventIdsInOutbox("StockUpdated");

		this.relay.pollOnce();

		assertThat(this.jdbc.sql("SELECT status FROM outbox_message WHERE destination = 'KAFKA' ORDER BY id")
			.query(String.class)
			.list()).containsExactly("DELIVERED", "PENDING");
		this.relay.pollOnce();
		List<ConsumerRecord<String, String>> records = consume("ordersync.stock.v1", eventIds);
		assertThat(records).extracting((record) -> header(record, "eventId")).containsExactlyElementsOf(eventIds);
		assertThat(records).extracting(ConsumerRecord::key).containsOnly("TORN-M6-100");
		assertThat(records.get(0).partition()).isEqualTo(records.get(1).partition());
		assertThat(records.get(0).offset()).isLessThan(records.get(1).offset());
	}

	// Breaks if: a failed send is treated as delivered. The broker is paused (not stopped, so it keeps its port): the
	// event waits in the outbox and goes out afterwards, through the same producer.
	@Test
	void eventSurvivesKafkaBeingUnavailableAndIsPublishedOnceItIsBack() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-5\"}")));
		postSignedOrder("evt-k5", ORDER_1001);
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(1)).containsEntry("status", "DONE");
		});

		this.kafka.getDockerClient().pauseContainerCmd(this.kafka.getContainerId()).exec();
		try {
			this.relay.pollOnce();
		}
		finally {
			this.kafka.getDockerClient().unpauseContainerCmd(this.kafka.getContainerId()).exec();
		}

		assertThat(outboxRow(1)).containsEntry("status", "RETRY").containsEntry("attempts", 1);
		assertThat((String) outboxRow(1).get("last_error")).contains("Kafka");
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			this.relay.pollOnce();
			assertThat(outboxRow(1)).containsEntry("status", "DELIVERED");
		});
		assertThat(consume("ordersync.orders.v1", eventIdsInOutbox("OrderSynced"))).isNotEmpty();
	}

	// Breaks if: the event is written outside the transaction of the DONE update (the job would be DONE with no
	// event, and nobody downstream would ever hear about the order).
	@Test
	void failingToRecordTheEventRollsBackTheDone() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-6\"}")));
		this.jdbc.sql("""
				CREATE FUNCTION reject_kafka_event() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'rejected by test'; END $$ LANGUAGE plpgsql
				""").update();
		this.jdbc.sql("""
				CREATE TRIGGER reject_kafka_event BEFORE INSERT ON outbox_message
				FOR EACH ROW WHEN (NEW.destination = 'KAFKA') EXECUTE FUNCTION reject_kafka_event()
				""").update();
		postSignedOrder("evt-k6", ORDER_1001);

		this.worker.pollOnce();

		assertThat(jobRow(1)).containsEntry("status", "IN_PROGRESS");
		assertThat(countRows("outbox_message")).isZero();
		this.jdbc.sql("DROP TRIGGER reject_kafka_event ON outbox_message").update();
		this.jdbc.sql("UPDATE sync_job SET locked_until = now() - interval '1 second'").update();
		this.worker.pollOnce();
		assertThat(jobRow(1)).containsEntry("status", "DONE");
		assertThat(eventIdsInOutbox("OrderSynced")).hasSize(1);
	}

	// Breaks if: the event is written even when the lease guard rejects the result. A worker whose lease expired
	// (the ERP took 800 ms, the lease is 200 ms) would add a second OrderSynced with its own eventId, which consumers
	// could not recognise as a duplicate.
	@Test
	void lateResultAfterALeaseTakeoverWritesNoSecondEvent() throws Exception {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withFixedDelay(800)
			.withBody("{\"orderId\":\"ERP-8\"}")));
		postSignedOrder("evt-k8", ORDER_1001);

		try (ExecutorService slowWorker = Executors.newSingleThreadExecutor()) {
			Future<?> first = slowWorker.submit(this.worker::pollOnce);
			await().atMost(Duration.ofSeconds(5))
				.until(() -> this.jdbc.sql("SELECT locked_until < now() FROM sync_job WHERE id = 1")
					.query(Boolean.class)
					.single());
			this.worker.pollOnce();
			first.get();
		}

		assertThat(jobRow(1)).containsEntry("status", "DONE");
		assertThat(this.erp.find(postRequestedFor(urlEqualTo(SALES_ORDERS)))).hasSize(2);
		assertThat(eventIdsInOutbox("OrderSynced")).hasSize(1);
	}

	private static String stockUpdate(long quantity, String changedAt) {
		return """
				{"articleCode":"TORN-M6-100","quantityAvailable":%d,"warehouse":"BCN","changedAt":"%s"}
				""".formatted(quantity, changedAt);
	}

}
