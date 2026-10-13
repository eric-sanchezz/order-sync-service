package dev.ericsanchez.ordersync.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
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

class ConcurrentDuplicateWebhookTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DataSource dataSource;

	// Breaks if: the inbox INSERT loses ON CONFLICT DO NOTHING (the waiting requests get a unique violation -> 500),
	// or the duplicate branch looks the job up before the winner commits. Deterministic: an open transaction holds
	// the event id, so all four requests are queued on that row lock before it is released.
	@Test
	void fourSimultaneousDeliveriesOfTheSameEventCreateOneJob() throws Exception {
		List<Future<ResponseEntity<String>>> responses = new ArrayList<>();
		try (Connection blocker = this.dataSource.getConnection();
				ExecutorService shop = Executors.newFixedThreadPool(4)) {
			blocker.setAutoCommit(false);
			try (PreparedStatement hold = blocker.prepareStatement(
					"INSERT INTO inbox_event (event_id, payload, received_at) VALUES ('evt-race', '{}', now())")) {
				hold.executeUpdate();
			}
			for (int i = 0; i < 4; i++) {
				responses.add(shop.submit(() -> postSignedOrder("evt-race", ORDER)));
			}
			await().atMost(Duration.ofSeconds(5)).until(() -> this.jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%inbox_event%'
					""").query(Long.class).single() == 4);
			blocker.rollback();
		}

		List<Integer> statuses = new ArrayList<>();
		List<String> bodies = new ArrayList<>();
		for (Future<ResponseEntity<String>> response : responses) {
			statuses.add(response.get().getStatusCode().value());
			bodies.add(response.get().getBody());
		}
		assertThat(statuses).containsExactlyInAnyOrder(202, 200, 200, 200);
		assertThat(bodies).allMatch((body) -> body.contains("\"jobId\":1"));
		assertThat(countRows("inbox_event")).isEqualTo(1);
		assertThat(countRows("sync_job")).isEqualTo(1);
	}

}
