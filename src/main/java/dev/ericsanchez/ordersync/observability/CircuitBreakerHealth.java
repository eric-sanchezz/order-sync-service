package dev.ericsanchez.ordersync.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Health of an integration, read from its circuit breaker (no ping to the remote system). CLOSED is UP; any other
 * state, including FORCED_OPEN, METRICS_ONLY or DISABLED, is DEGRADED. DEGRADED is not DOWN: the service keeps
 * accepting webhooks and queueing work while the remote system is unavailable, so it must not be restarted or taken
 * out of the load balancer for that.
 */
final class CircuitBreakerHealth {

	static final Status DEGRADED = new Status("DEGRADED");

	private CircuitBreakerHealth() {
	}

	static Health of(CircuitBreaker circuitBreaker) {
		CircuitBreaker.State state = circuitBreaker.getState();
		Health.Builder health = (state == CircuitBreaker.State.CLOSED) ? Health.up() : Health.status(DEGRADED);
		return health.withDetail("circuitBreaker", circuitBreaker.getName())
			.withDetail("state", state.name())
			.withDetail("failureRate", circuitBreaker.getMetrics().getFailureRate())
			.build();
	}

}
