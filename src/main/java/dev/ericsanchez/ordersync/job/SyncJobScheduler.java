package dev.ericsanchez.ordersync.job;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/** Runs {@link SyncWorker#pollOnce()} with a fixed delay. Disabled in tests, which call the worker directly. */
@Component
@ConditionalOnBooleanProperty(name = "ordersync.worker.enabled", matchIfMissing = true)
public class SyncJobScheduler implements SchedulingConfigurer {

	private final SyncWorker worker;

	private final OrderSyncProperties properties;

	public SyncJobScheduler(SyncWorker worker, OrderSyncProperties properties) {
		this.worker = worker;
		this.properties = properties;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addFixedDelayTask(this.worker::pollOnce, this.properties.worker().pollInterval());
	}

}
