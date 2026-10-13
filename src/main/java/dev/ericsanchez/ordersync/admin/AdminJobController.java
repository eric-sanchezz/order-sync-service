package dev.ericsanchez.ordersync.admin;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import dev.ericsanchez.ordersync.job.JobStatus;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Operator API (needs {@code X-API-Key}): inspect jobs, replay DEAD ones, and read queue stats. */
@RestController
@RequestMapping("/admin")
public class AdminJobController {

	private final AdminJobService service;

	public AdminJobController(AdminJobService service) {
		this.service = service;
	}

	private static final int MAX_PAGE_SIZE = 100;

	/** Keeps page * size (the SQL offset, an int in Spring Data) from overflowing. */
	private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

	/** An unknown {@code status} value is rejected with 400 by the enum conversion. */
	@GetMapping("/jobs")
	public JobPage list(@RequestParam(required = false) JobStatus status,
			@RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return this.service.list(status, page, size);
	}

	@GetMapping("/jobs/{id}")
	public JobDetail detail(@PathVariable long id) {
		return this.service.detail(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job " + id + " not found"));
	}

	/** Only DEAD jobs can be replayed; the event id is reused as Idempotency-Key (see ADR 0002). */
	@PostMapping("/jobs/{id}/replay")
	public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
		return switch (this.service.replay(id)) {
			case REPLAYED -> ResponseEntity.ok(Map.of("jobId", id, "status", JobStatus.PENDING));
			case NOT_DEAD -> ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("jobId", id, "error", "only DEAD jobs can be replayed"));
			case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "job " + id + " not found");
		};
	}

	@GetMapping("/stats")
	public JobStats stats() {
		return this.service.stats();
	}

}
