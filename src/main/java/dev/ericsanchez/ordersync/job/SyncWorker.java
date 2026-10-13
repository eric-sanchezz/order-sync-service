package dev.ericsanchez.ordersync.job;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.erp.ErpClient;
import dev.ericsanchez.ordersync.erp.ErpResult;
import dev.ericsanchez.ordersync.observability.SyncMetrics;
import dev.ericsanchez.ordersync.transform.ErpOrder;
import dev.ericsanchez.ordersync.transform.OrderTransformer;
import dev.ericsanchez.ordersync.webhook.ShopOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Claims due jobs and syncs each one to the ERP on its own virtual thread. Three short steps per job, none holding a
 * transaction across the network: (1) claim with a lease, (2) HTTP call, (3) store the result guarded by the lease.
 */
@Component
public class SyncWorker {

	private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

	private final SyncJobRepository jobs;

	private final CustomerMappingRepository customers;

	private final ErpClient erpClient;

	private final BackoffPolicy backoff;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final OrderSyncProperties properties;

	private final SyncMetrics metrics;

	public SyncWorker(SyncJobRepository jobs, CustomerMappingRepository customers, ErpClient erpClient,
			BackoffPolicy backoff, JsonMapper jsonMapper, Clock clock, OrderSyncProperties properties,
			SyncMetrics metrics) {
		this.jobs = jobs;
		this.customers = customers;
		this.erpClient = erpClient;
		this.backoff = backoff;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.properties = properties;
		this.metrics = metrics;
	}

	/** Claims up to {@code batch-size} due jobs and returns once all of them have been processed. */
	public void pollOnce() {
		OffsetDateTime now = now();
		List<ClaimedJob> claimed = this.jobs.claim(now, now.plus(this.properties.worker().lease()),
				this.properties.worker().batchSize());
		if (claimed.isEmpty()) {
			return;
		}
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (ClaimedJob job : claimed) {
				executor.execute(() -> processSafely(job));
			}
		}
	}

	/** One failing job must not take the rest of the batch down; its lease expires and it is claimed again. */
	private void processSafely(ClaimedJob job) {
		// Set here, inside the job's own virtual thread: MDC is per thread.
		MDC.put("eventId", job.eventId());
		MDC.put("jobId", String.valueOf(job.id()));
		try {
			process(job);
		}
		catch (RuntimeException ex) {
			log.error("Job {} ({}) failed unexpectedly; it will be retried when its lease expires", job.id(),
					job.eventId(), ex);
		}
		finally {
			MDC.clear();
		}
	}

	private void process(ClaimedJob job) {
		int maxAttempts = this.properties.retry().maxAttempts();
		if (job.attempts() >= maxAttempts) {
			// Only reachable through expired leases: each one counts as an attempt whose result was lost.
			finishDead(job, job.attempts(),
					"gave up after " + job.attempts() + " attempts without a stored result (lease expired)");
			return;
		}
		ErpOrder order;
		try {
			ShopOrder shopOrder = this.jsonMapper.readValue(job.payload(), ShopOrder.class);
			OrderTransformer.Result result = OrderTransformer.transform(shopOrder,
					this.customers::findErpCustomerCode);
			switch (result) {
				case OrderTransformer.Invalid invalid -> {
					finishDead(job, job.attempts(), invalid.reason());
					return;
				}
				case OrderTransformer.Ok ok -> order = ok.order();
			}
		}
		catch (RuntimeException ex) {
			// A transformation bug must end in DEAD, never in an endless RETRY loop.
			finishDead(job, job.attempts(), "transformation failed: " + ex);
			return;
		}

		ErpResult result;
		try {
			result = this.erpClient.createSalesOrder(job.eventId(), order);
		}
		catch (RuntimeException ex) {
			result = new ErpResult.Retryable("unexpected error: " + ex);
		}
		recordResult(job, result);
	}

	private void recordResult(ClaimedJob job, ErpResult result) {
		OffsetDateTime now = now();
		int attempts = job.attempts() + 1;
		switch (result) {
			case ErpResult.Success success -> {
				log.info("Job {} ({}) DONE, ERP order {}", job.id(), job.eventId(), success.orderId());
				store(job, attempts, "the ERP accepted the order",
						() -> this.jobs.markDone(job, attempts, success.orderId(), now));
			}
			case ErpResult.Permanent permanent -> {
				log.warn("Job {} ({}) DEAD: {}", job.id(), job.eventId(), permanent.error());
				countDead(store(job, attempts, "the ERP rejected the order",
						() -> this.jobs.markDead(job, attempts, permanent.error(), now)));
			}
			case ErpResult.Retryable retryable when attempts >= this.properties.retry().maxAttempts() -> {
				log.warn("Job {} ({}) DEAD after {} attempts: {}", job.id(), job.eventId(), attempts,
						retryable.error());
				countDead(store(job, attempts, "the ERP call failed",
						() -> this.jobs.markDead(job, attempts, retryable.error(), now)));
			}
			case ErpResult.Retryable retryable -> {
				Duration delay = this.backoff.delay(attempts);
				log.info("Job {} ({}) RETRY in {} ms (attempt {}): {}", job.id(), job.eventId(), delay.toMillis(),
						attempts, retryable.error());
				if (store(job, attempts, "the ERP call failed",
						() -> this.jobs.markRetry(job, attempts, now.plus(delay), retryable.error(), now))) {
					this.metrics.jobRetried();
				}
			}
			case ErpResult.CircuitOpen open -> {
				// The ERP was not called, so this does not consume an attempt.
				Duration delay = this.properties.retry().circuitOpenDelay();
				log.info("Job {} ({}) postponed {} ms: ERP circuit breaker is open", job.id(), job.eventId(),
						delay.toMillis());
				store(job, job.attempts(), "the circuit breaker was open",
						() -> this.jobs.markRetry(job, job.attempts(), now.plus(delay), "circuit breaker open", now));
			}
		}
	}

	private void finishDead(ClaimedJob job, int attempts, String reason) {
		log.warn("Job {} ({}) DEAD: {}", job.id(), job.eventId(), reason);
		countDead(store(job, attempts, "the order could not be sent",
				() -> this.jobs.markDead(job, attempts, reason, now())));
	}

	private void countDead(boolean stored) {
		if (stored) {
			this.metrics.jobDead();
		}
	}

	/**
	 * Runs the result write and returns true if it was stored as intended. Two kinds of failure are handled
	 * differently (see ADR 0002):
	 * <ul>
	 * <li>The database rejects the <em>data</em> ({@link DataIntegrityViolationException}). Writing it again would fail
	 * the same way after every lease expiry, so the job goes DEAD with a fixed message that does not carry the
	 * offending value (this second write cannot fail for the same reason).</li>
	 * <li>Anything else (timeout, cancelled statement, lost connection, deadlock...) may be gone in a second. The
	 * exception propagates and the job stays IN_PROGRESS: when its lease expires it is claimed again, that counts an
	 * attempt, and the ERP is called with the same Idempotency-Key, so the order is neither lost nor duplicated.</li>
	 * </ul>
	 */
	private boolean store(ClaimedJob job, int attempts, String outcome, BooleanSupplier write) {
		try {
			boolean stored = write.getAsBoolean();
			if (!stored) {
				logLostLease(job);
			}
			return stored;
		}
		catch (DataIntegrityViolationException ex) {
			log.error("Job {} ({}): {} but the result could not be stored; marking it DEAD", job.id(), job.eventId(),
					outcome, ex);
			countDead(this.jobs.markDead(job, attempts, "result could not be stored (" + outcome + "): "
					+ ex.getMostSpecificCause().getClass().getSimpleName()
					+ ". Check the ERP before replaying this job.", now()));
			return false;
		}
	}

	private static void logLostLease(ClaimedJob job) {
		log.warn("Job {} ({}) result discarded: its lease expired and another worker owns it now", job.id(),
				job.eventId());
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

}
