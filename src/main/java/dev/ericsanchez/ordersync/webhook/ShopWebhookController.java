package dev.ericsanchez.ordersync.webhook;

import java.util.Optional;

import dev.ericsanchez.ordersync.observability.SyncMetrics;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
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

	private static final String SOURCE = "shop";

	private static final Logger log = LoggerFactory.getLogger(ShopWebhookController.class);

	private final HmacVerifier verifier;

	private final JsonMapper jsonMapper;

	private final InboxService inboxService;

	private final SyncMetrics metrics;

	public ShopWebhookController(@Qualifier("shopWebhookVerifier") HmacVerifier verifier, JsonMapper jsonMapper,
			InboxService inboxService, SyncMetrics metrics) {
		this.verifier = verifier;
		this.jsonMapper = jsonMapper;
		this.inboxService = inboxService;
		this.metrics = metrics;
	}

	public record AcceptedResponse(long jobId, String status) {
	}

	/** {@code jobId} is the job of the same shop order (or of the same event). */
	public record DuplicateResponse(Long jobId, boolean duplicate) {
	}

	public record InProgressResponse(long jobId, String error) {
	}

	public record ErrorResponse(String error) {
	}

	@PostMapping(path = "/webhooks/shop/orders", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> receiveOrder(@RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId,
			@RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
			@RequestBody(required = false) byte[] body) {
		boolean validEventId = eventId != null && !eventId.isBlank() && eventId.length() <= MAX_EVENT_ID_LENGTH;
		if (validEventId) {
			MDC.put("eventId", eventId);
		}
		try {
			return receive(eventId, validEventId, signature, (body != null) ? body : new byte[0]);
		}
		finally {
			MDC.remove("eventId");
		}
	}

	private ResponseEntity<?> receive(String eventId, boolean validEventId, String signature, byte[] rawBody) {
		if (!this.verifier.verify(rawBody, signature)) {
			log.warn("Rejected shop webhook with a missing or invalid signature");
			this.metrics.webhookReceived(SOURCE, "unauthorized");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("invalid signature"));
		}
		if (!validEventId) {
			this.metrics.webhookReceived(SOURCE, "bad_request");
			return ResponseEntity.badRequest()
				.body(new ErrorResponse(EVENT_ID_HEADER + " must be 1 to " + MAX_EVENT_ID_LENGTH + " characters"));
		}
		Optional<String> json = Utf8Body.decode(rawBody);
		Optional<ShopOrder> order = json.flatMap(this::parse);
		if (order.isEmpty()) {
			this.metrics.webhookReceived(SOURCE, "bad_request");
			return ResponseEntity.badRequest().body(new ErrorResponse("body is not a readable UTF-8 order JSON"));
		}
		InboxService.IngestResult result = this.inboxService.ingest(eventId, json.get(), order.get().id());
		return switch (result) {
			case InboxService.Accepted accepted -> {
				log.info("Accepted shop event {} as job {}", eventId, accepted.jobId());
				this.metrics.webhookReceived(SOURCE, "accepted");
				yield ResponseEntity.status(HttpStatus.ACCEPTED)
					.body(new AcceptedResponse(accepted.jobId(), "PENDING"));
			}
			case InboxService.InProgress inProgress -> {
				log.info("Shop event {} refused: job {} of that order is being sent to the ERP right now", eventId,
						inProgress.jobId());
				this.metrics.webhookReceived(SOURCE, "conflict");
				yield ResponseEntity.status(HttpStatus.CONFLICT)
					.body(new InProgressResponse(inProgress.jobId(),
							"the order is being sent to the ERP right now; retry later"));
			}
			case InboxService.Duplicate duplicate -> {
				log.info("Duplicate shop event {} (same event or same shop order; job {})", eventId,
						duplicate.jobId());
				this.metrics.webhookReceived(SOURCE, "duplicate");
				yield ResponseEntity.ok(new DuplicateResponse(duplicate.jobId(), true));
			}
		};
	}

	/**
	 * Only unreadable JSON is rejected here; missing fields are the transformer's job (the order goes DEAD). Raw
	 * control characters such as the NULs of a UTF-16 body are not valid JSON, so they end here too.
	 */
	private Optional<ShopOrder> parse(String json) {
		try {
			return Optional.ofNullable(this.jsonMapper.readValue(json, ShopOrder.class));
		}
		catch (JacksonException unreadable) {
			return Optional.empty();
		}
	}

}
