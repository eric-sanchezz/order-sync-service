package dev.ericsanchez.ordersync.stock;

import dev.ericsanchez.ordersync.config.HttpClients;
import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Beans of the stock sync: the ERP webhook's verifier and the shop client. They live here, not in {@code config},
 * so that {@code config} does not depend on this package.
 */
@Configuration(proxyBeanMethods = false)
public class StockConfig {

	/** The ERP signs with its own secret; a body signed with the shop's secret must not pass here. */
	@Bean
	HmacVerifier erpWebhookVerifier(OrderSyncProperties properties) {
		return new HmacVerifier(properties.erpWebhook().secret());
	}

	@Bean
	RestClient shopRestClient(OrderSyncProperties properties) {
		OrderSyncProperties.Shop shop = properties.shop();
		return HttpClients.restClient(shop.baseUrl(), shop.timeout())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + shop.apiToken())
			.build();
	}

	/** Same rules as the ERP breaker: only retryable failures (5xx, 408, 429, timeouts, IO) count. */
	@Bean
	CircuitBreaker shopCircuitBreaker(CircuitBreakerRegistry registry, OrderSyncProperties properties) {
		return registry.circuitBreaker("shop",
				properties.shop().circuitBreaker().toConfig(ShopStockDispatcher::isRecordedFailure));
	}

}
