package dev.ericsanchez.ordersync.events;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-aggregate sequence numbers for domain events: 1, 2, 3... for each order and each SKU. The upsert takes the row
 * lock of the aggregate and keeps it until the caller's transaction ends, so a second writer of the same aggregate
 * waits, and gets the next number only after the first has committed (or rolled back, giving its number back).
 */
@Component
class EventSequences {

	private final JdbcClient jdbc;

	EventSequences(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	long next(String aggregateType, String aggregateId) {
		return this.jdbc.sql("""
				INSERT INTO event_sequence (aggregate_type, aggregate_id, last_sequence)
				VALUES (:aggregateType, :aggregateId, 1)
				ON CONFLICT (aggregate_type, aggregate_id)
				DO UPDATE SET last_sequence = event_sequence.last_sequence + 1
				RETURNING last_sequence
				""")
			.param("aggregateType", aggregateType)
			.param("aggregateId", aggregateId)
			.query(Long.class)
			.single();
	}

}
