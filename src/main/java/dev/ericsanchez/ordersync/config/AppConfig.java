package dev.ericsanchez.ordersync.config;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;

import dev.ericsanchez.ordersync.retry.BackoffPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared infrastructure beans. The beans of each integration live in its own package ({@code erp.ErpConfig},
 * {@code webhook.WebhookConfig}, {@code stock.StockConfig}), so that {@code config} depends on none of them and the
 * packages stay free of cycles (checked by {@code ArchitectureTest}).
 */
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
	CircuitBreakerRegistry circuitBreakerRegistry() {
		return CircuitBreakerRegistry.ofDefaults();
	}

}
