package dev.ericsanchez.ordersync.outbox;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/** Runs {@link OutboxRelay#pollOnce()} with a fixed delay. Disabled in tests, which call the relay directly. */
@Component
@ConditionalOnBooleanProperty(name = "ordersync.outbox.relay-enabled", matchIfMissing = true)
public class OutboxRelayScheduler implements SchedulingConfigurer {

	private final OutboxRelay relay;

	private final OrderSyncProperties properties;

	public OutboxRelayScheduler(OutboxRelay relay, OrderSyncProperties properties) {
		this.relay = relay;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(this.relay::pollOnce, this.properties.outbox().pollInterval());
	}

}
