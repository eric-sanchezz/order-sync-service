package dev.ericsanchez.ordersync.config;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;

import dev.ericsanchez.ordersync.erp.ErpClient;
import dev.ericsanchez.ordersync.job.BackoffPolicy;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderSyncProperties.class)
public class AppConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/** {@link Random} is thread-safe, which matters because jobs run on parallel virtual threads. */
	@Bean
	RandomGenerator randomGenerator() {
		return new Random();
	}

	@Bean
	BackoffPolicy backoffPolicy(OrderSyncProperties properties, RandomGenerator random) {
		OrderSyncProperties.Retry retry = properties.retry();
		return new BackoffPolicy(retry.baseDelay(), retry.maxDelay(), retry.jitter(), random);
	}

	@Bean
	HmacVerifier shopWebhookVerifier(OrderSyncProperties properties) {
		return new HmacVerifier(properties.webhook().secret());
	}

	@Bean
	RestClient erpRestClient(OrderSyncProperties properties) {
		return HttpClients.restClient(properties.erp().baseUrl(), properties.erp().timeout()).build();
	}

	@Bean
	CircuitBreakerRegistry circuitBreakerRegistry() {
		return CircuitBreakerRegistry.ofDefaults();
	}

	/**
	 * Only retryable failures (5xx, 408, 429, timeouts, IO) count against the breaker. A 4xx means the ERP is up and
	 * rejected one order, so it must not open the circuit for everybody else.
	 */
	@Bean
	CircuitBreaker erpCircuitBreaker(CircuitBreakerRegistry registry, OrderSyncProperties properties) {
		return registry.circuitBreaker("erp", properties.erp().circuitBreaker().toConfig(ErpClient::isRecordedFailure));
	}

}
