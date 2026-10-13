package dev.ericsanchez.ordersync.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LongSummaryStatistics;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

	// Breaks if: the exponent is n instead of n-1 (the first retry would wait 4 s instead of 2 s).
	@Test
	void delayDoublesWithEachAttempt() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(1)).isEqualTo(Duration.ofSeconds(2));
		assertThat(backoff.delay(2)).isEqualTo(Duration.ofSeconds(4));
		assertThat(backoff.delay(3)).isEqualTo(Duration.ofSeconds(8));
	}

	// Breaks if: the cap check is removed (attempt 9 would be 512 s).
	@Test
	void delayIsCappedAtMax() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(9)).isEqualTo(Duration.ofMinutes(5));
	}

	// Breaks if: the n > 30 shortcut is removed (1L << 64 is 1 in Java, so attempt 65 would wait only 2 s).
	@Test
	void hugeAttemptNumbersUseMaxInsteadOfOverflowing() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(31)).isEqualTo(Duration.ofMinutes(5));
		assertThat(backoff.delay(65)).isEqualTo(Duration.ofMinutes(5));
	}

	// Breaks if: jitter draws from [0, d] or [d, 2d] instead of [d/2, d], or is not applied at all.
	@Test
	void jitterStaysBetweenHalfAndFullDelay() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), true,
				new SplittableRandom(42));

		LongSummaryStatistics delaysMillis = IntStream.range(0, 1_000)
			.mapToLong((i) -> backoff.delay(3).toMillis())
			.summaryStatistics();

		assertThat(delaysMillis.getMin()).isGreaterThanOrEqualTo(4_000);
		assertThat(delaysMillis.getMax()).isLessThanOrEqualTo(8_000);
		assertThat(delaysMillis.getMin()).isLessThan(4_500);
		assertThat(delaysMillis.getMax()).isGreaterThan(7_500);
	}

}
