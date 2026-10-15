package dev.ericsanchez.ordersync.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.observability.SyncMetrics;
import dev.ericsanchez.ordersync.outbox.ClaimedMessage;
import dev.ericsanchez.ordersync.outbox.DeliveryResult;
import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxDispatcher;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the outbox's KAFKA messages. It is one more {@link OutboxDispatcher}, so it inherits everything the relay
 * does: claim with a lease, in order per key (the record key is the aggregate id, so one key keeps its order inside
 * one partition), retries with backoff, replay. Two differences with the shop (ADR 0006):
 * <ul>
 * <li>Retryable failures (broker away, timeouts) never send an event DEAD: it is retried, with capped backoff, until
 * Kafka takes it, and while the {@code kafka} circuit breaker is open the attempts are not even used up. Only an
 * event Kafka can never accept (too large, unserializable, invalid topic, not authorized) goes DEAD.</li>
 * <li>A DEAD event blocks the newer events of its key until it is replayed (see the relay's claim).</li>
 * </ul>
 * SUPERSEDED and the staleness guard of the shop's stock messages do not apply: events are history, not state.
 * <p>
 * Delivery is at least once: if the acknowledgement is lost after the broker stored the event, the relay sends it
 * again. Consumers deduplicate by the {@code eventId} header (also in the JSON).
 */
@Component
public class KafkaDispatcher implements OutboxDispatcher {

	private final KafkaTemplate<String, String> kafka;

	private final JsonMapper jsonMapper;

	private final OrderSyncProperties.Kafka properties;

	private final SyncMetrics metrics;

	private final CircuitBreaker circuitBreaker;

	public KafkaDispatcher(KafkaTemplate<String, String> kafka, JsonMapper jsonMapper, OrderSyncProperties properties,
			SyncMetrics metrics, @Qualifier("kafkaCircuitBreaker") CircuitBreaker circuitBreaker) {
		this.kafka = kafka;
		this.circuitBreaker = circuitBreaker;
		this.jsonMapper = jsonMapper;
		this.properties = properties.events().kafka();
		this.metrics = metrics;
		metrics.registerEventTypes(Arrays.stream(DomainEventType.values()).map(DomainEventType::eventName).toList());
	}

	/** Thrown inside the breaker so that only retryable failures are recorded; never leaves this class. */
	private static final class RetryableFailure extends RuntimeException {

		private final DeliveryResult.Retryable result;

		RetryableFailure(DeliveryResult.Retryable result) {
			super(result.error(), null, false, false);
			this.result = result;
		}

	}

	/** Circuit breaker predicate: only retryable failures count. */
	public static boolean isRecordedFailure(Throwable throwable) {
		return throwable instanceof RetryableFailure;
	}

	@Override
	public OutboxDestination destination() {
		return OutboxDestination.KAFKA;
	}

	@Override
	public boolean retriesUntilDelivered() {
		return true;
	}

	@Override
	public DeliveryResult deliver(ClaimedMessage message) {
		Optional<DomainEventType> type = DomainEventType.fromEventName(message.eventType());
		if (type.isEmpty()) {
			return new DeliveryResult.Permanent("unknown event type: " + message.eventType());
		}
		ProducerRecord<String, String> record = new ProducerRecord<>(topic(type.get()), message.aggregateId(),
				message.payload());
		JsonNode envelope = this.jsonMapper.readTree(message.payload());
		header(record, "eventId", envelope.path("eventId").asString(null));
		header(record, "eventType", message.eventType());
		header(record, "correlationId", envelope.path("correlationId").asString(null));
		try {
			return this.circuitBreaker.executeSupplier(() -> {
				DeliveryResult result = send(message, record);
				if (result instanceof DeliveryResult.Retryable retryable) {
					throw new RetryableFailure(retryable);
				}
				return result;
			});
		}
		catch (CallNotPermittedException open) {
			return new DeliveryResult.CircuitOpen();
		}
		catch (RetryableFailure failure) {
			return failure.result;
		}
	}

	private DeliveryResult send(ClaimedMessage message, ProducerRecord<String, String> record) {
		Duration timeout = this.properties.sendTimeout();
		try {
			this.kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			this.metrics.eventPublished(message.eventType());
			return new DeliveryResult.Delivered();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return failed(message, "retryable", new DeliveryResult.Retryable("interrupted while waiting for Kafka"));
		}
		catch (TimeoutException ex) {
			return failed(message, "retryable",
					new DeliveryResult.Retryable("no acknowledgement from Kafka within " + timeout.toMillis() + " ms"));
		}
		catch (ExecutionException | RuntimeException ex) {
			// RuntimeException: KafkaTemplate throws synchronously, e.g. when the metadata wait (max.block.ms) runs out.
			Throwable cause = (ex instanceof ExecutionException) ? ex.getCause() : ex;
			if (isPermanent(cause)) {
				return failed(message, "permanent",
						new DeliveryResult.Permanent("Kafka rejected the event: " + rootCause(cause)));
			}
			return failed(message, "retryable", new DeliveryResult.Retryable("Kafka send failed: " + rootCause(cause)));
		}
	}

	/**
	 * The innermost cause: spring-kafka wraps every failure in "KafkaException: Send failed", and last_error (all the
	 * operator sees in /admin/outbox) must say what Kafka actually refused.
	 */
	private static Throwable rootCause(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root;
	}

	private String topic(DomainEventType type) {
		return switch (type.aggregate()) {
			case ORDER -> this.properties.ordersTopic();
			case STOCK -> this.properties.stockTopic();
		};
	}

	private static void header(ProducerRecord<String, String> record, String name, String value) {
		if (value != null) {
			record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
		}
	}

	private DeliveryResult failed(ClaimedMessage message, String outcome, DeliveryResult result) {
		this.metrics.eventFailed(message.eventType(), outcome);
		return result;
	}

	/**
	 * Only what sending again cannot fix: the event is too large, cannot be serialized, the topic name is invalid, or
	 * we are not allowed to write. Everything else (timeouts, a broker away, not enough replicas, leader changes...)
	 * is worth another try.
	 */
	private static boolean isPermanent(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
					|| cause instanceof InvalidTopicException || cause instanceof AuthorizationException
					|| cause instanceof AuthenticationException) {
				return true;
			}
		}
		return false;
	}

}
