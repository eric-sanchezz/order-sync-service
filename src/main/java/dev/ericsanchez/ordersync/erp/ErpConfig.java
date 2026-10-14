package dev.ericsanchez.ordersync.erp;

import dev.ericsanchez.ordersync.config.HttpClients;
import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Beans of the ERP client. They live here, not in {@code config}, so that {@code config} does not depend on this
 * package.
 */
@Configuration(proxyBeanMethods = false)
public class ErpConfig {

	@Bean
	RestClient erpRestClient(OrderSyncProperties properties) {
		return HttpClients.restClient(properties.erp().baseUrl(), properties.erp().timeout()).build();
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
