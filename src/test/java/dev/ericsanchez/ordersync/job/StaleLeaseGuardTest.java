package dev.ericsanchez.ordersync.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Worker A claims a job and stalls past its lease; worker B re-claims it. A's late result must be discarded and must
 * not overwrite the job B now owns; B's result is the one stored.
 */
class StaleLeaseGuardTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private SyncJobRepository jobs;

	// Breaks if: the result updates drop "AND locked_until = :lockedUntil" (A's late DEAD would overwrite B's job).
	@Test
	void lateResultFromAWorkerWhoseLeaseExpiredIsDiscarded() {
		postSignedOrder("evt-stale-lease", ORDER);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

		List<ClaimedJob> claimedByA = this.jobs.claim(now, now.minusSeconds(1), 5);
		List<ClaimedJob> claimedByB = this.jobs.claim(now, now.plusSeconds(60), 5);
		assertThat(claimedByA).hasSize(1);
		assertThat(claimedByB).hasSize(1);

		boolean lateWriteByA = this.jobs.markDead(claimedByA.get(0), 1, "HTTP 422: late answer", now);
		boolean writeByB = this.jobs.markDone(claimedByB.get(0), 1, "ERP-B", now);

		assertThat(lateWriteByA).isFalse();
		assertThat(writeByB).isTrue();
		assertThat(jobRow(claimedByB.get(0).id())).containsEntry("status", "DONE")
			.containsEntry("erp_order_id", "ERP-B")
			.containsEntry("last_error", null);
	}

}
