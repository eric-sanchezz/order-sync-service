package dev.ericsanchez.ordersync.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns scheduling on. Each poller ({@code SyncJobScheduler}, {@code OutboxRelayScheduler}) has its own on/off
 * property; with both off (as in tests, which call {@code pollOnce()} directly) nothing is scheduled.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
