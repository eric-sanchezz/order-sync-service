package dev.ericsanchez.ordersync.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** {@code components.erp} in /actuator/health: the state of the ERP circuit breaker. */
@Component
public class ErpHealthIndicator implements HealthIndicator {

	private final CircuitBreaker circuitBreaker;

	public ErpHealthIndicator(@Qualifier("erpCircuitBreaker") CircuitBreaker circuitBreaker) {
		this.circuitBreaker = circuitBreaker;
	}

	@Override
	public Health health() {
		return CircuitBreakerHealth.of(this.circuitBreaker);
	}

}
