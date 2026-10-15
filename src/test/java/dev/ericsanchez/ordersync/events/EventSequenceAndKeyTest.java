package dev.ericsanchez.ordersync.events;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import dev.ericsanchez.ordersync.KafkaIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tester, H6 round 2: the per-aggregate sequence on rollback, and what a DEAD event blocks. */
class EventSequenceAndKeyTest extends KafkaIntegrationTest {

	private static final String ORDER_1001_UNKNOWN_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50", "customer": { "id": 999 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DomainEvents events;

	@Autowired
	private PlatformTransactionManager transactionManager;

	// Breaks if: the sequence is taken outside the event's transaction (REQUIRES_NEW, a Postgres SEQUENCE...): the
	// rolled-back change would burn number 1 and the first event a consumer ever sees would be sequence 2.
	@Test
	void aRolledBackEventGivesItsSequenceBack() {
		TransactionTemplate transactions = new TransactionTemplate(this.transactionManager);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		transactions.executeWithoutResult((status) -> {
			this.events.stockUpdated("ROLLBACK-SKU", 5, "BCN", Instant.parse("2026-10-13T10:00:00Z"), "rb-1", now);
			status.setRollbackOnly();
		});

		transactions.executeWithoutResult((status) -> this.events.stockUpdated("ROLLBACK-SKU", 7, "BCN",
				Instant.parse("2026-10-13T10:01:00Z"), "rb-2", now));

		assertThat(this.jdbc.sql("""
				SELECT (payload::jsonb ->> 'sequence')::bigint FROM outbox_message WHERE aggregate_id = 'ROLLBACK-SKU'
				""").query(Long.class).list()).containsExactly(1L);
	}

	// Order 1001 and the SKU "1001" are different aggregates (different topics, different sequences): a DEAD event of
	// the order must not hold back the SKU's events. Numeric article codes are accepted by the stock webhook.
	// Breaks if: the head-of-line check of the claim matches older messages by aggregate_id only, without the
	// aggregate type.
	@Test
	void aDeadOrderEventDoesNotBlockAStockEventWithTheSameId() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-1\"}")));
		postSignedOrder("evt-order-1001", ORDER_1001_UNKNOWN_CUSTOMER);
		this.worker.pollOnce();
		this.jdbc.sql("UPDATE outbox_message SET status = 'DEAD', last_error = 'forced by the test' WHERE id = 1")
			.update();

		postSignedStockUpdate("erp-sku-1001", """
				{"articleCode":"1001","quantityAvailable":3,"warehouse":"BCN","changedAt":"2026-10-13T10:00:00Z"}
				""");
		this.relay.pollOnce();
		this.relay.pollOnce();

		assertThat(this.jdbc.sql("""
				SELECT status FROM outbox_message WHERE destination = 'KAFKA' AND event_type = 'StockUpdated'
				""").query(String.class).single()).isEqualTo("DELIVERED");
	}

}
