package dev.ericsanchez.ordersync.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Uses the well-known HMAC-SHA256 test vector (key "key", "The quick brown fox..."). */
class HmacVerifierTest {

	private static final byte[] BODY = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

	private static final String HEX = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";

	// Breaks if: the HMAC uses another algorithm, encoding or key.
	@Test
	void acceptsTheCorrectSignature() {
		HmacVerifier verifier = new HmacVerifier("key");

		assertThat(verifier.verify(BODY, "sha256=" + HEX)).isTrue();
	}

	// Breaks if: sign() builds the header with another prefix, encoding or digest than the one verify() expects.
	@Test
	void signProducesTheHeaderTheShopSends() {
		HmacVerifier verifier = new HmacVerifier("key");

		assertThat(verifier.sign(BODY)).isEqualTo("sha256=" + HEX);
	}

	// Breaks if: verify() always returns true, or compares only a prefix of the bytes.
	@Test
	void rejectsABodyWithOneAlteredByte() {
		HmacVerifier verifier = new HmacVerifier("key");
		byte[] tampered = BODY.clone();
		tampered[tampered.length - 1] = 'G';

		assertThat(verifier.verify(tampered, "sha256=" + HEX)).isFalse();
	}

	// Breaks if: the "sha256=" prefix is optional.
	@Test
	void rejectsASignatureWithoutPrefix() {
		HmacVerifier verifier = new HmacVerifier("key");

		assertThat(verifier.verify(BODY, HEX)).isFalse();
	}

	// Breaks if: invalid hex throws instead of returning false (the webhook would answer 500, not 401).
	@Test
	void rejectsInvalidHex() {
		HmacVerifier verifier = new HmacVerifier("key");

		assertThat(verifier.verify(BODY, "sha256=not-hex-at-all")).isFalse();
	}

}
