package dev.ericsanchez.ordersync.webhook;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop order webhook. The body arrives as raw bytes so the HMAC is checked on exactly what the shop signed. Checks run
 * in a fixed order: signature, event id, JSON parsing, and only then storage.
 * No API key here ({@code @SecurityRequirements} empty): the shop authenticates with the HMAC signature.
 */
@RestController
@SecurityRequirements
public class ShopWebhookController {

	public static final String EVENT_ID_HEADER = "X-Shop-Event-Id";

	public static final String SIGNATURE_HEADER = "X-Shop-Signature";

	private static final int MAX_EVENT_ID_LENGTH = 200;

	/** U+FEFF, which Windows tools such as Notepad put at the start of UTF-8 files. */
	private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

	private static final Logger log = LoggerFactory.getLogger(ShopWebhookController.class);

	private final HmacVerifier verifier;

	private final JsonMapper jsonMapper;

	private final InboxService inboxService;

	public ShopWebhookController(HmacVerifier verifier, JsonMapper jsonMapper, InboxService inboxService) {
		this.verifier = verifier;
		this.jsonMapper = jsonMapper;
		this.inboxService = inboxService;
	}

	public record AcceptedResponse(long jobId, String status) {
	}

	public record DuplicateResponse(long jobId, boolean duplicate) {
	}

	public record ErrorResponse(String error) {
	}

	@PostMapping(path = "/webhooks/shop/orders", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> receiveOrder(@RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId,
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
			@RequestBody(required = false) byte[] body) {
		byte[] rawBody = (body != null) ? body : new byte[0];
		if (!this.verifier.verify(rawBody, signature)) {
			log.warn("Rejected shop webhook with a missing or invalid signature");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("invalid signature"));
		}
		if (eventId == null || eventId.isBlank() || eventId.length() > MAX_EVENT_ID_LENGTH) {
			return ResponseEntity.badRequest()
				.body(new ErrorResponse(EVENT_ID_HEADER + " must be 1 to " + MAX_EVENT_ID_LENGTH + " characters"));
		}
		Optional<String> json = decodeUtf8(rawBody);
		if (json.isEmpty() || !isReadableOrder(json.get())) {
			return ResponseEntity.badRequest().body(new ErrorResponse("body is not a readable UTF-8 order JSON"));
		}
		InboxService.IngestResult result = this.inboxService.ingest(eventId, json.get());
		return switch (result) {
			case InboxService.Accepted accepted -> {
				log.info("Accepted shop event {} as job {}", eventId, accepted.jobId());
				yield ResponseEntity.status(HttpStatus.ACCEPTED)
					.body(new AcceptedResponse(accepted.jobId(), "PENDING"));
			}
			case InboxService.Duplicate duplicate -> {
				log.info("Duplicate shop event {} (job {})", eventId, duplicate.jobId());
				yield ResponseEntity.ok(new DuplicateResponse(duplicate.jobId(), true));
			}
		};
	}

	/**
	 * Strict UTF-8 decoding (malformed bytes are an error, not replacement characters), minus a leading BOM. The
	 * result is both what gets checked here and what gets stored, so the worker later parses exactly the text that
	 * passed this check. Letting Jackson sniff the encoding of the raw bytes instead would accept UTF-16 or a BOM here
	 * and then fail when the stored text is parsed.
	 */
	private static Optional<String> decodeUtf8(byte[] body) {
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

	/**
	 * Only unreadable JSON is rejected here; missing fields are the transformer's job (the order goes DEAD). Raw
	 * control characters such as the NULs of a UTF-16 body are not valid JSON, so they end here too.
	 */
	private boolean isReadableOrder(String json) {
		try {
			return this.jsonMapper.readValue(json, ShopOrder.class) != null;
		}
		catch (JacksonException unreadable) {
			return false;
		}
	}

}
