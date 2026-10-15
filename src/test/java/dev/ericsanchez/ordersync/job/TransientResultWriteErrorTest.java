package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ERP accepts the order, and the DONE write hits a one-off database error (here: the statement is cancelled
 * while it waits for a row lock). That is a transient failure, not a value that can never be stored, so the job must
 * not be written off as DEAD.
 */
class TransientResultWriteErrorTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JsonMapper json;

	// Breaks if: SyncWorker.store() catches any DataAccessException again (a transient error would send DEAD an
	// order the ERP already created).
	@Test
	void cancelledDoneWriteAfterAnErpSuccessDoesNotSendTheJobDead() throws Exception {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-T\"}")
			.withFixedDelay(300)));
		long jobId = this.json.readTree(postSignedOrder("evt-transient", ORDER).getBody()).get("jobId").asLong();

		// Not on the common pool: the JDK HttpClient completes responses there, and on a 2-CPU machine (GitHub's
		// runner) its only thread would be this pollOnce, waiting for that same response.
		CompletableFuture<Void> poll = CompletableFuture.runAsync(this.worker::pollOnce, Thread::startVirtualThread);
		await().atMost(Duration.ofSeconds(5)).until(() -> "IN_PROGRESS".equals(jobRow(jobId).get("status")));
		try (Connection blocker = this.dataSource.getConnection(); Statement sql = blocker.createStatement()) {
			blocker.setAutoCommit(false);
			sql.execute("SELECT id FROM sync_job WHERE id = " + jobId + " FOR UPDATE");
			await().atMost(Duration.ofSeconds(5)).until(() -> waitingUpdateSetting("DONE") == 1);
			this.jdbc.sql("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
					+ " AND query LIKE '%status = ''DONE''%'").query().listOfRows();
			await().atMost(Duration.ofSeconds(5))
				.until(() -> poll.isDone() || waitingUpdateSetting("DEAD") == 1);
			blocker.rollback();
		}
		await().atMost(Duration.ofSeconds(5)).until(poll::isDone);

		assertThat(jobRow(jobId).get("status")).isNotEqualTo("DEAD");
	}

	private long waitingUpdateSetting(String status) {
		return this.jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
				+ " AND query LIKE '%SET status = ''" + status + "''%'").query(Long.class).single();
	}

}
