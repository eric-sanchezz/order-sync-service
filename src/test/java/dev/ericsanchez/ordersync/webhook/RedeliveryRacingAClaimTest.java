package dev.ericsanchez.ordersync.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/**
 * A corrected delivery of order 1001 arrives just as a worker claims the PENDING job. The worker's transaction
 * (played here by the test) row-locks the job, then turns it IN_PROGRESS and commits. The webhook must see the
 * committed IN_PROGRESS and refuse with 409; it must not swap the payload of a job that is already being sent.
 */
class RedeliveryRacingAClaimTest extends IntegrationTest {

	private static final String ORDER_FOR_CUSTOMER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": %d },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DataSource dataSource;

	// Breaks if: InboxService.redeliver() reads the job status without FOR UPDATE. A row lock only (no update yet)
	// does not stop the INSERT ... ON CONFLICT, so without FOR UPDATE the webhook reads PENDING, its UPDATE waits on
	// the lock, and after the commit it re-points the IN_PROGRESS job to the new payload and answers 202.
	@Test
	void redeliveryThatReadsTheJobWhileAWorkerIsClaimingItIsRefusedAndLeavesThePayloadAlone() throws Exception {
		postSignedOrder("evt-original", ORDER_FOR_CUSTOMER.formatted(42));
		long originalInboxId = inboxEventOfJob();

		CompletableFuture<ResponseEntity<String>> redelivery;
		try (Connection worker = this.dataSource.getConnection(); Statement sql = worker.createStatement()) {
			worker.setAutoCommit(false);
			sql.execute("SELECT id FROM sync_job WHERE shop_order_id = 1001 FOR UPDATE");
			redelivery = CompletableFuture.supplyAsync(
					() -> postSignedOrder("evt-corrected", ORDER_FOR_CUSTOMER.formatted(77)));
			await().atMost(Duration.ofSeconds(5)).until(() -> this.jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%sync_job%'
					""").query(Long.class).single() == 1);
			sql.execute("UPDATE sync_job SET status = 'IN_PROGRESS', locked_until = now() + interval '60 seconds'"
					+ " WHERE shop_order_id = 1001");
			worker.commit();
		}
		ResponseEntity<String> response = redelivery.get();

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(inboxEventOfJob()).isEqualTo(originalInboxId);
		assertThat(countRows("inbox_event")).isEqualTo(1);
	}

	private long inboxEventOfJob() {
		return this.jdbc.sql("SELECT inbox_event_id FROM sync_job WHERE shop_order_id = 1001")
			.query(Long.class)
			.single();
	}

}
