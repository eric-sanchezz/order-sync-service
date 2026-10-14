package dev.ericsanchez.ordersync.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with a cap: {@code delay(n) = min(max, base * 2^(n-1))} for the n-th failed attempt (n >= 1).
 * With jitter on, the delay is uniform in {@code [d/2, d]} so that jobs that failed together do not retry together.
 */
public final class BackoffPolicy {

	/** Past this attempt the cap is used directly; it also keeps the shift below 64 bits (Java masks shift counts). */
	private static final int MAX_EXPONENT_ATTEMPT = 30;

	private final long baseMillis;

	private final long maxMillis;

	private final boolean jitter;

	private final RandomGenerator random;

	public BackoffPolicy(Duration base, Duration max, boolean jitter, RandomGenerator random) {
		if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
			throw new IllegalArgumentException("need 0 < base <= max, got base=" + base + " max=" + max);
		}
		this.baseMillis = base.toMillis();
		this.maxMillis = max.toMillis();
		this.jitter = jitter;
		this.random = random;
	}

	public Duration delay(int attempt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("attempt starts at 1, got " + attempt);
		}
		long delay = exponentialDelay(attempt);
		if (this.jitter) {
			long half = delay / 2;
			delay = half + this.random.nextLong(delay - half + 1);
		}
		return Duration.ofMillis(delay);
	}

	private long exponentialDelay(int attempt) {
		if (attempt > MAX_EXPONENT_ATTEMPT) {
			return this.maxMillis;
		}
		long factor = 1L << (attempt - 1);
		// The cap, written as a division so that base * factor is only computed when it fits under max.
		if (this.baseMillis > this.maxMillis / factor) {
			return this.maxMillis;
		}
		return this.baseMillis * factor;
	}

}
