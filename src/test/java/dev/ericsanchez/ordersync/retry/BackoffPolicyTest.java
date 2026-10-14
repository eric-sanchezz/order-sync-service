package dev.ericsanchez.ordersync.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LongSummaryStatistics;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
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

	// Breaks if: the cap check (base > max / factor, in integer millis) uses >=: with a cap of 8003 ms, max / 4 is
	// 2000 = base, so attempt 3 would get the cap (8003 ms) instead of 2 s x 4 = 8000 ms.
	@Test
	void delayJustBelowACapThatIsNotAMultipleIsNotRoundedUpToIt() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMillis(8_003), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(3)).isEqualTo(Duration.ofMillis(8_000));
		assertThat(backoff.delay(4)).isEqualTo(Duration.ofMillis(8_003));
	}

	// Breaks if: base == max is refused (the check becomes max <= base): a constant delay is a valid policy.
	@Test
	void baseEqualToMaxGivesAConstantDelay() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(5), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(1)).isEqualTo(Duration.ofSeconds(5));
		assertThat(backoff.delay(4)).isEqualTo(Duration.ofSeconds(5));
	}

	// Breaks if: the shortcut starts at n >= 30 instead of n > 30 (attempt 30 is still exponential when max allows).
	@Test
	void attemptThirtyIsStillExponential() {
		BackoffPolicy backoff = new BackoffPolicy(Duration.ofMillis(1), Duration.ofDays(365), false,
				new SplittableRandom(1));

		assertThat(backoff.delay(30)).isEqualTo(Duration.ofMillis(1L << 29));
		assertThat(backoff.delay(31)).isEqualTo(Duration.ofDays(365));
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

	// Breaks if: the jitter range loses an end, e.g. nextLong(d - d/2 - 1) instead of + 1 (the full delay would
	// never be drawn). The generator returns the lowest and then the highest value it is allowed to.
	@Test
	void jitterBoundsAreInclusive() {
		RandomGenerator lowest = new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public long nextLong(long bound) {
				return 0;
			}
		};
		RandomGenerator highest = new RandomGenerator() {
			@Override
			public long nextLong() {
				return Long.MAX_VALUE;
			}

			@Override
			public long nextLong(long bound) {
				return bound - 1;
			}
		};

		assertThat(new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), true, lowest).delay(3))
			.isEqualTo(Duration.ofSeconds(4));
		assertThat(new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), true, highest).delay(3))
			.isEqualTo(Duration.ofSeconds(8));
	}

}
