package dev.ericsanchez.ordersync.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class ConcurrentSameOrderDifferentEventTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DataSource dataSource;

	// Breaks if: the sync_job insert loses ON CONFLICT (shop_order_id) (the waiting requests hit the UNIQUE index and
	// answer 500), or the duplicate lookup does not find the job committed by the other transaction (jobId null).
	// Deterministic: an open transaction owns order 1001, so all four requests queue on that index entry first.
	@Test
	void fourDeliveriesOfOneOrderUnderDifferentEventIdsWhileItIsBeingStoredAllGetTheSameJob() throws Exception {
		List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
		long ownerJobId;
		try (Connection owner = this.dataSource.getConnection(); Statement sql = owner.createStatement();
				ExecutorService shop = Executors.newFixedThreadPool(4)) {
			owner.setAutoCommit(false);
			sql.execute("INSERT INTO inbox_event (event_id, payload, received_at) VALUES ('evt-owner', '{}', now())");
			try (ResultSet job = sql.executeQuery("""
					INSERT INTO sync_job (inbox_event_id, event_id, shop_order_id, status, attempts, next_attempt_at,
					                      created_at, updated_at)
					SELECT id, 'evt-owner', 1001, 'PENDING', 0, now(), now(), now() FROM inbox_event
					WHERE event_id = 'evt-owner' RETURNING id""")) {
				job.next();
				ownerJobId = job.getLong(1);
			}
			for (int i = 0; i < 4; i++) {
				String eventId = "evt-other-" + i;
				responses.add(shop.submit(() -> postSignedOrder(eventId, ORDER)));
			}
			await().atMost(Duration.ofSeconds(5)).until(() -> this.jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%sync_job%'
					""").query(Long.class).single() == 4);
			owner.commit();
		}

		for (Future<ResponseEntity<String>> response : responses) {
			// Adapted by the coder (H2 round 3): a new delivery of a PENDING order now replaces its payload (202, same
			// job) instead of being a 200 duplicate. The point of the test is unchanged: no 500, one job, same id.
			assertThat(response.get().getStatusCode().value()).isEqualTo(202);
			assertThat(response.get().getBody()).isEqualTo("{\"jobId\":" + ownerJobId + ",\"status\":\"PENDING\"}");
		}
		assertThat(countRows("sync_job")).isEqualTo(1);
		assertThat(countRows("inbox_event")).isEqualTo(5);
	}

}
