package dev.ericsanchez.ordersync.events;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code kafka} circuit breaker, from the shared registry (so it shows up in the metrics next to {@code erp} and
 * {@code shop}). It lives here, not in {@code config}, so that {@code config} does not depend on this package.
 */
@Configuration(proxyBeanMethods = false)
public class EventsConfig {

	/** Only retryable failures (broker away, timeouts) count: a rejected event says nothing about the broker. */
	@Bean
	CircuitBreaker kafkaCircuitBreaker(CircuitBreakerRegistry registry, OrderSyncProperties properties) {
		return registry.circuitBreaker("kafka",
				properties.events().kafka().circuitBreaker().toConfig(KafkaDispatcher::isRecordedFailure));
	}

}
