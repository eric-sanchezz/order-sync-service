package dev.ericsanchez.ordersync.stock;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;
import dev.ericsanchez.ordersync.observability.SyncMetrics;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import dev.ericsanchez.ordersync.webhook.Utf8Body;
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
 * ERP stock webhook. Same gatekeeping as the shop webhook, with the ERP's own secret: signature on the raw bytes,
 * event id, strict UTF-8 JSON, field validation, and only then the transaction. Nothing is stored for a rejected
 * request. No API key here ({@code @SecurityRequirements} empty): the ERP authenticates with the HMAC signature.
 */
@RestController
@SecurityRequirements
public class ErpStockWebhookController {

	public static final String EVENT_ID_HEADER = "X-Erp-Event-Id";

	public static final String SIGNATURE_HEADER = "X-Erp-Signature";

	private static final int MAX_EVENT_ID_LENGTH = 200;

	private static final String SOURCE = "erp";

	private static final Logger log = LoggerFactory.getLogger(ErpStockWebhookController.class);

	private final HmacVerifier verifier;

	private final JsonMapper jsonMapper;

	private final StockUpdateService stockUpdates;

	private final Clock clock;

	private final OrderSyncProperties properties;

	private final SyncMetrics metrics;

	public ErpStockWebhookController(@Qualifier("erpWebhookVerifier") HmacVerifier verifier, JsonMapper jsonMapper,
			StockUpdateService stockUpdates, Clock clock, OrderSyncProperties properties, SyncMetrics metrics) {
		this.verifier = verifier;
		this.jsonMapper = jsonMapper;
		this.stockUpdates = stockUpdates;
		this.clock = clock;
		this.properties = properties;
		this.metrics = metrics;
	}

	public record AcceptedResponse(long outboxId, String status) {
	}

	public record DuplicateResponse(boolean duplicate) {
	}

	public record StaleResponse(boolean stale) {
	}

	public record ErrorResponse(String error) {
	}

	@PostMapping(path = "/webhooks/erp/stock", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> receiveStock(@RequestHeader(name = EVENT_ID_HEADER, required = false) String eventId,
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
			log.warn("Rejected ERP stock webhook with a missing or invalid signature");
			this.metrics.webhookReceived(SOURCE, "unauthorized");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("invalid signature"));
		}
		if (!validEventId) {
			this.metrics.webhookReceived(SOURCE, "bad_request");
			return ResponseEntity.badRequest()
				.body(new ErrorResponse(EVENT_ID_HEADER + " must be 1 to " + MAX_EVENT_ID_LENGTH + " characters"));
		}
		Optional<String> json = Utf8Body.decode(rawBody);
		Optional<StockChange> parsed = json.flatMap(this::parse);
		if (parsed.isEmpty()) {
			this.metrics.webhookReceived(SOURCE, "bad_request");
			return ResponseEntity.badRequest().body(new ErrorResponse("body is not a readable UTF-8 stock JSON"));
		}
		Instant latestAccepted = this.clock.instant().plus(this.properties.stock().maxClockSkew());
		StockChange.Validation validation = parsed.get().validate(latestAccepted);
		if (validation instanceof StockChange.Invalid invalid) {
			this.metrics.webhookReceived(SOURCE, "bad_request");
			return ResponseEntity.badRequest().body(new ErrorResponse(invalid.reason()));
		}
		StockLevelChanged change = ((StockChange.Valid) validation).change();
		return switch (this.stockUpdates.apply(eventId, json.get(), change)) {
			case StockUpdateService.Accepted accepted -> {
				log.info("Accepted ERP stock event {} for {} (quantity {}) as outbox message {}", eventId,
						change.articleCode(), change.quantityAvailable(), accepted.outboxId());
				this.metrics.webhookReceived(SOURCE, "accepted");
				yield ResponseEntity.status(HttpStatus.ACCEPTED)
					.body(new AcceptedResponse(accepted.outboxId(), "PENDING"));
			}
			case StockUpdateService.Duplicate duplicate -> {
				log.info("Duplicate ERP stock event {}", eventId);
				this.metrics.webhookReceived(SOURCE, "duplicate");
				yield ResponseEntity.ok(new DuplicateResponse(true));
			}
			case StockUpdateService.Stale stale -> {
				log.info("Stale ERP stock event {} for {} (changed at {}): a newer level is already stored", eventId,
						change.articleCode(), change.changedAt());
				this.metrics.webhookReceived(SOURCE, "stale");
				yield ResponseEntity.ok(new StaleResponse(true));
			}
		};
	}

	private Optional<StockChange> parse(String json) {
		try {
			return Optional.ofNullable(this.jsonMapper.readValue(json, StockChange.class));
		}
		catch (JacksonException unreadable) {
			return Optional.empty();
		}
	}

}
