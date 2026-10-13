package dev.ericsanchez.ordersync.stock;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxRepository;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a verified ERP stock update in ONE transaction: inbox (dedup), stock level, and the outbox message for the
 * shop. Either all of it is stored or none of it, so there is no "stock updated but the shop never hears about it"
 * (the dual-write problem, ADR 0004).
 */
@Service
public class StockUpdateService {

	private final JdbcClient jdbc;

	private final StockLevelRepository stockLevels;

	private final OutboxRepository outbox;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	public StockUpdateService(JdbcClient jdbc, StockLevelRepository stockLevels, OutboxRepository outbox,
			JsonMapper jsonMapper, Clock clock) {
		this.jdbc = jdbc;
		this.stockLevels = stockLevels;
		this.outbox = outbox;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	public sealed interface UpdateResult {
	}

	public record Accepted(long outboxId) implements UpdateResult {
	}

	public record Duplicate() implements UpdateResult {
	}

	/** Strictly older than the stored stock level: kept in the inbox, nothing else changes. */
	public record Stale() implements UpdateResult {
	}

	@Transactional
	public UpdateResult apply(String eventId, String payload, StockLevelChanged change) {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC)
			.truncatedTo(ChronoUnit.MICROS);
		Optional<Long> inboxEventId = this.jdbc.sql("""
				INSERT INTO erp_inbox_event (event_id, payload, received_at)
				VALUES (:eventId, :payload, :now)
				ON CONFLICT (event_id) DO NOTHING
				RETURNING id
				""")
			.param("eventId", eventId)
			.param("payload", payload)
			.param("now", now)
			.query(Long.class)
			.optional();
		if (inboxEventId.isEmpty()) {
			return new Duplicate();
		}
		if (!this.stockLevels.upsertIfNewer(change, now)) {
			return new Stale();
		}
		// Stock is an absolute value: once this message exists, older unsent ones for the article are pointless.
		this.outbox.supersedePending(change.articleCode(), OutboxDestination.SHOP_STOCK_API, now);
		long outboxId = this.outbox.enqueue(StockLevelChanged.AGGREGATE_TYPE, change.articleCode(),
				StockLevelChanged.EVENT_TYPE, OutboxDestination.SHOP_STOCK_API,
				this.jsonMapper.writeValueAsString(change), now);
		return new Accepted(outboxId);
	}

}
