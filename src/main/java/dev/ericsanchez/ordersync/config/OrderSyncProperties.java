package dev.ericsanchez.ordersync.config;

import java.net.URI;
import java.time.Duration;
import java.util.function.Predicate;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All settings under {@code ordersync.*}. Validated at startup: a blank secret or API key stops the app.
 * Nested records need {@code @Valid} or their constraints are silently skipped.
 */
@Validated
@ConfigurationProperties("ordersync")
public record OrderSyncProperties(
		@Valid @NotNull Webhook webhook,
		@Valid @NotNull Admin admin,
		@Valid @NotNull Erp erp,
		@Valid @NotNull Worker worker,
		@Valid @NotNull Retry retry,
		@Valid @NotNull ErpWebhook erpWebhook,
		@Valid @NotNull Shop shop,
		@Valid @NotNull Outbox outbox,
		@Valid @NotNull Stock stock,
		@Valid @NotNull Metrics metrics) {

	public record Webhook(@NotBlank String secret) {
	}

	public record Admin(@NotBlank String apiKey) {
	}

	public record Erp(@NotNull URI baseUrl, @NotNull Duration timeout, @Valid @NotNull CircuitBreaker circuitBreaker) {
	}

	public record CircuitBreaker(
			@Min(1) int slidingWindowSize,
			@Min(1) int minimumNumberOfCalls,
			@Min(1) @Max(100) int failureRateThreshold,
			@NotNull Duration waitDurationInOpenState,
			@Min(1) int permittedCallsInHalfOpenState) {

		/** Only exceptions matching {@code recordedFailure} count as failures; anything else counts as a success. */
		public CircuitBreakerConfig toConfig(Predicate<Throwable> recordedFailure) {
			return CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(this.slidingWindowSize)
				.minimumNumberOfCalls(this.minimumNumberOfCalls)
				.failureRateThreshold(this.failureRateThreshold)
				.waitDurationInOpenState(this.waitDurationInOpenState)
				.permittedNumberOfCallsInHalfOpenState(this.permittedCallsInHalfOpenState)
				.recordException(recordedFailure)
				.build();
		}

	}

	public record Worker(boolean enabled, @NotNull Duration pollInterval, @Min(1) int batchSize, @NotNull Duration lease) {
	}

	public record Retry(
			@Min(1) int maxAttempts,
			@NotNull Duration baseDelay,
			@NotNull Duration maxDelay,
			boolean jitter,
			@NotNull Duration circuitOpenDelay) {
	}

	/** HMAC secret of the ERP's stock webhook (not the shop's). */
	public record ErpWebhook(@NotBlank String secret) {
	}

	public record Shop(@NotNull URI baseUrl, @NotNull Duration timeout, @NotBlank String apiToken,
			@Valid @NotNull CircuitBreaker circuitBreaker) {
	}

	/** How far ahead of our clock an ERP stock change may be stamped (clock skew) before it is rejected. */
	public record Stock(@NotNull Duration maxClockSkew) {
	}

	/**
	 * The database gauges are refreshed in the background every {@code dbRefreshInterval}; each refresh may wait at
	 * most {@code dbQueryTimeout} for its connection and for its query.
	 */
	public record Metrics(@NotNull Duration dbRefreshInterval, @NotNull Duration dbQueryTimeout) {
	}

	public record Outbox(boolean relayEnabled, @NotNull Duration pollInterval, @Min(1) int batchSize,
			@NotNull Duration lease) {
	}

}
