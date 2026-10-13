package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;

import javax.sql.DataSource;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database gauges are a snapshot refreshed in the background. While the database cannot answer (here: sync_job
 * locked by another transaction, so every refresh hits its statement timeout), a scrape must stay fast, keep the last
 * values, and show through ordersync_db_snapshot_age_seconds that they are getting old.
 */
class DatabaseSnapshotTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DataSource dataSource;

	// Breaks if: a scrape queries the database itself (it would wait behind the lock), a failed refresh throws the
	// last values away, or the snapshot age does not grow while refreshes fail.
	@Test
	void whileTheDatabaseCannotAnswerTheScrapeStaysFastWithTheLastValuesAndAGrowingAge() throws Exception {
		postSignedOrder("evt-snapshot", ORDER);
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_jobs", "status=\"PENDING\"")).isEqualTo(1.0));

		try (Connection locker = this.dataSource.getConnection(); Statement sql = locker.createStatement()) {
			locker.setAutoCommit(false);
			sql.execute("LOCK TABLE sync_job IN ACCESS EXCLUSIVE MODE");

			await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(metric("ordersync_db_snapshot_age_seconds")).isGreaterThan(1.5));
			long start = System.nanoTime();
			double pending = metric("ordersync_jobs", "status=\"PENDING\"");
			long scrapeMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

			assertThat(scrapeMillis).isLessThan(1000);
			assertThat(pending).isEqualTo(1.0);
			locker.rollback();
		}
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(metric("ordersync_db_snapshot_age_seconds")).isLessThan(1.0));
	}

}
