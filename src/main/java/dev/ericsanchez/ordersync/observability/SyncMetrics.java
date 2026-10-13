package dev.ericsanchez.ordersync.observability;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * The service's counters and timers, in one place so that names and tags stay consistent. Counts that must survive a
 * restart or be exact (jobs per status, outbox per status) are not here: they are gauges read from the database
 * ({@link DatabaseGauges}).
 */
@Component
public class SyncMetrics {

	private final MeterRegistry registry;

	private final Counter jobRetries;

	private final Counter jobsDead;

	public SyncMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.jobRetries = Counter.builder("ordersync.jobs.retries")
			.description("Sync jobs rescheduled after a retryable ERP failure")
			.register(registry);
		this.jobsDead = Counter.builder("ordersync.jobs.dead")
			.description("Sync jobs that ended DEAD")
			.register(registry);
	}

	/** {@code outcome}: success, retryable, permanent or circuit_open. */
	public void recordErpRequest(String outcome, long durationNanos) {
		Timer.builder("ordersync.erp.requests")
			.description("Calls to the ERP sales-order API (circuit_open: refused locally, the ERP was not called)")
			.tag("outcome", outcome)
			.publishPercentileHistogram()
			.register(this.registry)
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	/** {@code outcome}: delivered, retryable, permanent or circuit_open. */
	public void recordShopRequest(String outcome, long durationNanos) {
		Timer.builder("ordersync.shop.requests")
			.description("Calls to the shop stock API")
			.tag("outcome", outcome)
			.publishPercentileHistogram()
			.register(this.registry)
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	public void jobRetried() {
		this.jobRetries.increment();
	}

	public void jobDead() {
		this.jobsDead.increment();
	}

	/**
	 * @param source shop or erp
	 * @param result accepted, duplicate, unauthorized, bad_request, stale (erp) or conflict (shop, order in flight)
	 */
	public void webhookReceived(String source, String result) {
		Counter.builder("ordersync.webhook.received")
			.description("Webhook deliveries by source and result")
			.tag("source", source)
			.tag("result", result)
			.register(this.registry)
			.increment();
	}

}
