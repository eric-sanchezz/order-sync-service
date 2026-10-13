package dev.ericsanchez.ordersync.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is off when the worker is disabled (tests call {@code SyncWorker.pollOnce()} directly). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ordersync.worker.enabled", matchIfMissing = true)
public class SchedulingConfig {

}
