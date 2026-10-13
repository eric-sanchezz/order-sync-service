package dev.ericsanchez.ordersync.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies {@code sha256=<hex HMAC-SHA256(secret, raw body)>} signatures. It works on the raw bytes, never on a
 * re-serialized object, and compares in constant time. Pure, so any signed webhook can reuse it.
 */
public final class HmacVerifier {

	private static final String ALGORITHM = "HmacSHA256";

	private static final String PREFIX = "sha256=";

	private final SecretKeySpec key;

	public HmacVerifier(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("HMAC secret must not be blank");
		}
		this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	public boolean verify(byte[] body, String signatureHeader) {
		if (body == null || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
			return false;
		}
		byte[] provided;
		try {
			provided = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException invalidHex) {
			return false;
		}
		return MessageDigest.isEqual(hmac(body), provided);
	}

	/** Builds the header value for {@code body}, exactly as the shop does. */
	public String sign(byte[] body) {
		return PREFIX + HexFormat.of().formatHex(hmac(body));
	}

	private byte[] hmac(byte[] body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(this.key);
			return mac.doFinal(body);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is not available", ex);
		}
	}

}
