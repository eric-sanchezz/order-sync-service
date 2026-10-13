package dev.ericsanchez.ordersync.stock;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Last known stock per article, as reported by the ERP. */
@Repository
public class StockLevelRepository {

	private final JdbcClient jdbc;

	public StockLevelRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Stores {@code change} unless it is strictly older than what is stored, in one statement. An equal timestamp is
	 * applied: ERPs often stamp to the second, and two changes in the same second must not lose the second one
	 * (between equals, arrival order decides; duplicates are already filtered by event id). The upsert also row-locks the article until the caller's transaction ends, so concurrent
	 * updates of one article are applied, and enqueued, one after the other.
	 * @return false if the change is stale (older than the stored one)
	 */
	public boolean upsertIfNewer(StockLevelChanged change, OffsetDateTime now) {
		return this.jdbc.sql("""
				INSERT INTO stock_level (article_code, quantity, warehouse, changed_at, updated_at)
				VALUES (:articleCode, :quantity, :warehouse, :changedAt, :now)
				ON CONFLICT (article_code) DO UPDATE
				SET quantity = EXCLUDED.quantity, warehouse = EXCLUDED.warehouse, changed_at = EXCLUDED.changed_at,
				    updated_at = EXCLUDED.updated_at
				WHERE stock_level.changed_at <= EXCLUDED.changed_at
				RETURNING article_code
				""")
			.param("articleCode", change.articleCode())
			.param("quantity", change.quantityAvailable())
			.param("warehouse", change.warehouse())
			.param("changedAt", OffsetDateTime.ofInstant(change.changedAt(), ZoneOffset.UTC))
			.param("now", now)
			.query(String.class)
			.optional()
			.isPresent();
	}

}
