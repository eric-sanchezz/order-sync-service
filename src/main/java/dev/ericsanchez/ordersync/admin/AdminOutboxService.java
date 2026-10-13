package dev.ericsanchez.ordersync.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import dev.ericsanchez.ordersync.outbox.OutboxMessage;
import dev.ericsanchez.ordersync.outbox.OutboxRepository;
import dev.ericsanchez.ordersync.outbox.OutboxStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Read and replay operations behind the outbox admin API, so the controller never touches a repository. */
@Service
public class AdminOutboxService {

	private final OutboxRepository messages;

	private final Clock clock;

	public AdminOutboxService(OutboxRepository messages, Clock clock) {
		this.messages = messages;
		this.clock = clock;
	}

	public OutboxPage list(OutboxStatus status, int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
		Page<OutboxMessage> result = (status != null) ? this.messages.findByStatus(status, request)
				: this.messages.findAll(request);
		return new OutboxPage(result.getContent().stream().map(OutboxSummary::from).toList(), result.getNumber(),
				result.getSize(), result.getTotalElements());
	}

	public Optional<OutboxDetail> detail(long id) {
		return this.messages.findById(id).map(OutboxDetail::from);
	}

	public ReplayOutcome replay(long id) {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC)
			.truncatedTo(ChronoUnit.MICROS);
		if (this.messages.replay(id, now)) {
			return ReplayOutcome.REPLAYED;
		}
		return this.messages.existsById(id) ? ReplayOutcome.NOT_DEAD : ReplayOutcome.NOT_FOUND;
	}

}
