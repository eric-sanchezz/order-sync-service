package dev.ericsanchez.ordersync.config;

import java.net.URI;
import java.time.Duration;

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
		@Valid @NotNull Retry retry) {

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

}
