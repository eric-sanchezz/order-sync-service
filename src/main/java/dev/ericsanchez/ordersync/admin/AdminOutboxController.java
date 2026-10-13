package dev.ericsanchez.ordersync.admin;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import dev.ericsanchez.ordersync.outbox.OutboxStatus;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Operator API for the outbox (needs {@code X-API-Key}): inspect messages and replay DEAD ones. */
@RestController
@RequestMapping("/admin/outbox")
public class AdminOutboxController {

	private static final int MAX_PAGE_SIZE = 100;

	/** Keeps page * size (the SQL offset, an int in Spring Data) from overflowing. */
	private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

	private final AdminOutboxService service;

	public AdminOutboxController(AdminOutboxService service) {
		this.service = service;
	}

	/** An unknown {@code status} value is rejected with 400 by the enum conversion. */
	@GetMapping
	public OutboxPage list(@RequestParam(required = false) OutboxStatus status,
			@RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return this.service.list(status, page, size);
	}

	@GetMapping("/{id}")
	public OutboxDetail detail(@PathVariable long id) {
		return this.service.detail(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "outbox message " + id + " not found"));
	}

	/**
	 * Only DEAD messages can be replayed. A replayed stock message that has meanwhile become older than the stored
	 * stock level is not sent: the dispatcher marks it SUPERSEDED (see ADR 0004).
	 */
	@PostMapping("/{id}/replay")
	public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
		return switch (this.service.replay(id)) {
			case REPLAYED -> ResponseEntity.ok(Map.of("outboxId", id, "status", OutboxStatus.PENDING));
			case NOT_DEAD -> ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("outboxId", id, "error", "only DEAD messages can be replayed"));
			case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					"outbox message " + id + " not found");
		};
	}

}
