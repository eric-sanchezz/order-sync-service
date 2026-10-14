package dev.ericsanchez.ordersync.webhook;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shop webhook's verifier. It lives here, not in {@code config}, so that {@code config} does not depend on this
 * package.
 */
@Configuration(proxyBeanMethods = false)
public class WebhookConfig {

	@Bean
	HmacVerifier shopWebhookVerifier(OrderSyncProperties properties) {
		return new HmacVerifier(properties.webhook().secret());
	}

}
