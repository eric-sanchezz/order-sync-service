package dev.ericsanchez.ordersync.events;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The event topics, created at startup by Spring's KafkaAdmin if they do not exist. Only when events are enabled:
 * without topics to create, the app does not try to reach a broker at startup. One replica, because the demo runs a
 * single broker; a real cluster would use 3 with {@code min.insync.replicas=2}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ordersync.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaTopicsConfig {

	@Bean
	NewTopic ordersTopic(OrderSyncProperties properties) {
		OrderSyncProperties.Kafka kafka = properties.events().kafka();
		return TopicBuilder.name(kafka.ordersTopic()).partitions(kafka.partitions()).replicas(1).build();
	}

	@Bean
	NewTopic stockTopic(OrderSyncProperties properties) {
		OrderSyncProperties.Kafka kafka = properties.events().kafka();
		return TopicBuilder.name(kafka.stockTopic()).partitions(kafka.partitions()).replicas(1).build();
	}

}
