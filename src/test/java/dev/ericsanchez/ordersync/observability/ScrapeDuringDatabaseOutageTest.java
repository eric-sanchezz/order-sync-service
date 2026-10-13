package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.zaxxer.hikari.HikariDataSource;
import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The database stops handing out connections (outage, or a pool drained by a stuck query). That is exactly when the
 * breaker state and ERP timers matter most, so /actuator/prometheus must still answer within Prometheus' default
 * 10 s scrape timeout, instead of waiting for a connection once per database gauge.
 */
class ScrapeDuringDatabaseOutageTest extends IntegrationTest {

	@Autowired
	private HikariDataSource dataSource;

	// Breaks if: a scrape reads the database through the application's pool again (each gauge would wait Hikari's
	// 30 s connectionTimeout and Prometheus would mark the target down). A synchronous read through the separate
	// one-connection metrics pool is NOT caught here; DatabaseSnapshotTest (coder) covers that.
	@Test
	void scrapeAnswersInTimeAndStillExportsTheBreakerStateWhenNoConnectionIsAvailable() throws Exception {
		List<Connection> held = new ArrayList<>();
		CompletableFuture<String> scrape;
		try {
			for (int i = 0; i < this.dataSource.getMaximumPoolSize(); i++) {
				held.add(this.dataSource.getConnection());
			}
			scrape = CompletableFuture.supplyAsync(
					() -> this.actuator.get().uri("/actuator/prometheus").retrieve().body(String.class));

			await().atMost(Duration.ofSeconds(10)).until(scrape::isDone);
		}
		finally {
			for (Connection connection : held) {
				connection.close();
			}
		}

		assertThat(scrape.get()).contains("resilience4j_circuitbreaker_state");
	}

}
