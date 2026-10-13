package dev.ericsanchez.ordersync.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code components.shop} in /actuator/health: the state of the shop circuit breaker. */
@Component
public class ShopHealthIndicator implements HealthIndicator {

	private final CircuitBreaker circuitBreaker;

	public ShopHealthIndicator(@Qualifier("shopCircuitBreaker") CircuitBreaker circuitBreaker) {
		this.circuitBreaker = circuitBreaker;
	}

	@Override
	public Health health() {
		return CircuitBreakerHealth.of(this.circuitBreaker);
	}

}
