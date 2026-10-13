package dev.ericsanchez.ordersync.stock;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Stock update as the ERP sends it. Every field is nullable so that {@link #validate()} can say exactly what is
 * wrong. {@code changedAt} is read as text and parsed here: an ISO-8601 timestamp with an offset is the only accepted
 * form (no epoch numbers, no local times whose zone would be a guess).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockChange(String articleCode, Long quantityAvailable, String warehouse, String changedAt) {

	static final int MAX_ARTICLE_CODE_LENGTH = 100;

	static final int MAX_WAREHOUSE_LENGTH = 50;

	/** Java parses years before what a Postgres timestamptz can hold; keep to ordinary dates. */
	static final int MIN_YEAR = 1;

	public sealed interface Validation {
	}

	public record Valid(StockLevelChanged change) implements Validation {
	}

	public record Invalid(String reason) implements Validation {
	}

	/**
	 * @param latestAcceptedChangedAt now plus the tolerated clock skew. A change stamped later than that would be
	 * "newer" than every real update to come and freeze the SKU, so it is rejected.
	 */
	public Validation validate(Instant latestAcceptedChangedAt) {
		if (this.articleCode == null || this.articleCode.isBlank()) {
			return new Invalid("missing field: articleCode");
		}
		if (this.articleCode.length() > MAX_ARTICLE_CODE_LENGTH) {
			return new Invalid("articleCode must be at most " + MAX_ARTICLE_CODE_LENGTH + " characters");
		}
		if (hasControlCharacters(this.articleCode)) {
			return new Invalid("articleCode must not contain control characters");
		}
		if (this.quantityAvailable == null) {
			return new Invalid("missing field: quantityAvailable");
		}
		if (this.quantityAvailable < 0) {
			return new Invalid("quantityAvailable must be >= 0");
		}
		if (this.warehouse != null && this.warehouse.length() > MAX_WAREHOUSE_LENGTH) {
			return new Invalid("warehouse must be at most " + MAX_WAREHOUSE_LENGTH + " characters");
		}
		if (this.warehouse != null && hasControlCharacters(this.warehouse)) {
			return new Invalid("warehouse must not contain control characters");
		}
		if (this.changedAt == null) {
			return new Invalid("missing field: changedAt");
		}
		OffsetDateTime parsed;
		try {
			parsed = OffsetDateTime.parse(this.changedAt);
		}
		catch (DateTimeParseException ex) {
			return new Invalid("changedAt must be an ISO-8601 timestamp with an offset, e.g. 2026-10-13T10:05:00Z");
		}
		if (parsed.withOffsetSameInstant(ZoneOffset.UTC).getYear() < MIN_YEAR) {
			return new Invalid("changedAt must not be before year " + MIN_YEAR);
		}
		if (parsed.toInstant().isAfter(latestAcceptedChangedAt)) {
			return new Invalid("changedAt is in the future (later than " + latestAcceptedChangedAt
					+ "); check the ERP's clock");
		}
		// Postgres keeps microseconds: compare and store the same precision everywhere.
		return new Valid(new StockLevelChanged(this.articleCode, this.quantityAvailable, this.warehouse,
				parsed.toInstant().truncatedTo(ChronoUnit.MICROS)));
	}

	private static boolean hasControlCharacters(String text) {
		return text.chars().anyMatch(Character::isISOControl);
	}

}
