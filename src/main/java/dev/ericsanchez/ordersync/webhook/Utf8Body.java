package dev.ericsanchez.ordersync.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decodes a webhook body once, strictly as UTF-8 (malformed bytes are an error, not replacement characters), minus a
 * leading BOM. The result is both what gets validated and what gets stored, so whoever reads the stored text later
 * parses exactly what passed validation. Letting Jackson sniff the encoding of the raw bytes instead would accept
 * UTF-16 or a BOM at the door and then fail when the stored text is parsed.
 */
public final class Utf8Body {

	/** U+FEFF, which Windows tools such as Notepad put at the start of UTF-8 files. */
	private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

	private Utf8Body() {
	}

	public static Optional<String> decode(byte[] body) {
		try {
			String text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(body))
				.toString();
			return Optional.of((!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) ? text.substring(1) : text);
		}
		catch (CharacterCodingException notUtf8) {
			return Optional.empty();
		}
	}

}
