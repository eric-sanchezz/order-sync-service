package dev.ericsanchez.ordersync.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.job.BackoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox messages, each on its own virtual thread, with the same three short steps as the order worker:
 * (1) claim with a lease (in order per key), (2) call the destination outside any transaction, (3) store the result
 * guarded by the lease. Retries, backoff, attempt budget and circuit-breaker handling follow the same rules too.
 */
@Component
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxRepository messages;

	private final Map<OutboxDestination, OutboxDispatcher> dispatchers = new EnumMap<>(OutboxDestination.class);

	private final BackoffPolicy backoff;

	private final Clock clock;

	private final OrderSyncProperties properties;

	public OutboxRelay(OutboxRepository messages, List<OutboxDispatcher> dispatchers, BackoffPolicy backoff,
			Clock clock, OrderSyncProperties properties) {
		this.messages = messages;
		for (OutboxDispatcher dispatcher : dispatchers) {
			OutboxDispatcher previous = this.dispatchers.put(dispatcher.destination(), dispatcher);
			if (previous != null) {
				throw new IllegalStateException("Two dispatchers for " + dispatcher.destination());
			}
		}
		this.backoff = backoff;
		this.clock = clock;
		this.properties = properties;
	}

	/** Claims up to {@code batch-size} due messages and returns once all of them have been processed. */
	public void pollOnce() {
		OffsetDateTime now = now();
		List<ClaimedMessage> claimed = this.messages.claim(now, now.plus(this.properties.outbox().lease()),
				this.properties.outbox().batchSize());
		if (claimed.isEmpty()) {
			return;
		}
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (ClaimedMessage message : claimed) {
				executor.execute(() -> processSafely(message));
			}
		}
	}

	/** One failing message must not take the rest of the batch down; its lease expires and it is claimed again. */
	private void processSafely(ClaimedMessage message) {
		// Set here, inside the message's own virtual thread: MDC is per thread.
		MDC.put("outboxId", String.valueOf(message.id()));
		MDC.put("aggregateId", message.aggregateId());
		try {
			process(message);
		}
		catch (RuntimeException ex) {
			log.error("Outbox message {} ({} {}) failed unexpectedly; it will be retried when its lease expires",
					message.id(), message.destination(), message.aggregateId(), ex);
		}
		finally {
			MDC.clear();
		}
	}

	private void process(ClaimedMessage message) {
		if (message.attempts() >= this.properties.retry().maxAttempts()) {
			// Only reachable through expired leases: each one counts as an attempt whose result was lost.
			finishDead(message, message.attempts(),
					"gave up after " + message.attempts() + " attempts without a stored result (lease expired)");
			return;
		}
		OutboxDispatcher dispatcher = this.dispatchers.get(message.destination());
		if (dispatcher == null) {
			finishDead(message, message.attempts(), "no dispatcher for destination " + message.destination());
			return;
		}
		DeliveryResult result;
		try {
			result = dispatcher.deliver(message);
		}
		catch (RuntimeException ex) {
			result = new DeliveryResult.Retryable("unexpected error: " + ex);
		}
		recordResult(message, result);
	}

	private void recordResult(ClaimedMessage message, DeliveryResult result) {
		OffsetDateTime now = now();
		int attempts = message.attempts() + 1;
		switch (result) {
			case DeliveryResult.Delivered delivered -> {
				log.info("Outbox message {} ({} {}) DELIVERED", message.id(), message.destination(),
						message.aggregateId());
				store(message, attempts, "the destination accepted the message",
						() -> this.messages.markDelivered(message, attempts, now));
			}
			case DeliveryResult.Superseded superseded -> {
				// Nothing was sent, so this does not consume an attempt.
				log.info("Outbox message {} ({} {}) SUPERSEDED: {}", message.id(), message.destination(),
						message.aggregateId(), superseded.reason());
				store(message, message.attempts(), "the message was superseded",
						() -> this.messages.markSuperseded(message, superseded.reason(), now));
			}
			case DeliveryResult.Permanent permanent -> {
				log.warn("Outbox message {} ({} {}) DEAD: {}", message.id(), message.destination(),
						message.aggregateId(), permanent.error());
				store(message, attempts, "the destination rejected the message",
						() -> this.messages.markDead(message, attempts, permanent.error(), now));
			}
			case DeliveryResult.Retryable retryable when attempts >= this.properties.retry().maxAttempts() -> {
				log.warn("Outbox message {} ({} {}) DEAD after {} attempts: {}", message.id(), message.destination(),
						message.aggregateId(), attempts, retryable.error());
				store(message, attempts, "the delivery failed",
						() -> this.messages.markDead(message, attempts, retryable.error(), now));
			}
			case DeliveryResult.Retryable retryable -> {
				Duration delay = this.backoff.delay(attempts);
				log.info("Outbox message {} ({} {}) RETRY in {} ms (attempt {}): {}", message.id(),
						message.destination(), message.aggregateId(), delay.toMillis(), attempts, retryable.error());
				store(message, attempts, "the delivery failed",
						() -> this.messages.markRetry(message, attempts, now.plus(delay), retryable.error(), now));
			}
			case DeliveryResult.CircuitOpen open -> {
				// The destination was not called, so this does not consume an attempt.
				Duration delay = this.properties.retry().circuitOpenDelay();
				log.info("Outbox message {} ({} {}) postponed {} ms: circuit breaker is open", message.id(),
						message.destination(), message.aggregateId(), delay.toMillis());
				store(message, message.attempts(), "the circuit breaker was open",
						() -> this.messages.markRetry(message, message.attempts(), now.plus(delay),
								"circuit breaker open", now));
			}
		}
	}

	private void finishDead(ClaimedMessage message, int attempts, String reason) {
		log.warn("Outbox message {} ({} {}) DEAD: {}", message.id(), message.destination(), message.aggregateId(),
				reason);
		store(message, attempts, "the message could not be sent",
				() -> this.messages.markDead(message, attempts, reason, now()));
	}

	/**
	 * Runs the result write, with the same rule as the order worker (ADR 0002): if the database rejects the data the
	 * message goes DEAD with a fixed message; any other error propagates, and the expired lease brings the message
	 * back with the same Idempotency-Key.
	 */
	private void store(ClaimedMessage message, int attempts, String outcome, BooleanSupplier write) {
		boolean stored;
		try {
			stored = write.getAsBoolean();
		}
		catch (DataIntegrityViolationException ex) {
			log.error("Outbox message {}: {} but the result could not be stored; marking it DEAD", message.id(),
					outcome, ex);
			stored = this.messages.markDead(message, attempts, "result could not be stored (" + outcome + "): "
					+ ex.getMostSpecificCause().getClass().getSimpleName()
					+ ". Check the destination before replaying this message.", now());
		}
		if (!stored) {
			log.warn("Outbox message {} result discarded: its lease expired and another relay owns it now",
					message.id());
		}
	}

	private OffsetDateTime now() {
		return OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

}
