package dev.ericsanchez.ordersync.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import dev.ericsanchez.ordersync.IntegrationTest;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The ERP stock webhook's gatekeeping, and the all-or-nothing transaction behind it. */
class ErpStockWebhookTest extends IntegrationTest {

	private static final String UPDATE = """
			{"articleCode":"TORN-M6-100","quantityAvailable":120,"warehouse":"BCN","changedAt":"2026-10-13T10:05:00Z"}
			""";

	@AfterEach
	void dropTrigger() {
		this.jdbc.sql("DROP TRIGGER IF EXISTS reject_outbox ON outbox_message").update();
		this.jdbc.sql("DROP FUNCTION IF EXISTS reject_outbox()").update();
	}

	static Stream<Arguments> badSignatures() {
		byte[] body = UPDATE.getBytes(StandardCharsets.UTF_8);
		return Stream.of(
				Arguments.of("missing", null),
				Arguments.of("signed with another secret", new HmacVerifier("not-the-secret").sign(body)),
				Arguments.of("signed with the shop's secret", new HmacVerifier(WEBHOOK_SECRET).sign(body)));
	}

	// Breaks if: the verifier always says yes, or the stock webhook is wired to the shop's verifier.
	@ParameterizedTest(name = "{0}")
	@MethodSource("badSignatures")
	void badSignatureIsRejectedAndNothingIsStored(String description, String signature) {
		ResponseEntity<String> response = post("erp-s2", signature, UPDATE.getBytes(StandardCharsets.UTF_8));

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(countRows("erp_inbox_event")).isZero();
		assertThat(countRows("stock_level")).isZero();
		assertThat(countRows("outbox_message")).isZero();
	}

	static Stream<Arguments> unusableBodies() {
		return Stream.of(
				Arguments.of("unreadable JSON", "{ not json"),
				Arguments.of("missing articleCode", "{\"quantityAvailable\":1,\"changedAt\":\"2026-10-13T10:05:00Z\"}"),
				Arguments.of("articleCode longer than 100 characters", "{\"articleCode\":\"" + "A".repeat(101)
						+ "\",\"quantityAvailable\":1,\"changedAt\":\"2026-10-13T10:05:00Z\"}"),
				Arguments.of("negative quantity",
						"{\"articleCode\":\"X\",\"quantityAvailable\":-1,\"changedAt\":\"2026-10-13T10:05:00Z\"}"),
				Arguments.of("fractional quantity",
						"{\"articleCode\":\"X\",\"quantityAvailable\":1.5,\"changedAt\":\"2026-10-13T10:05:00Z\"}"),
				Arguments.of("changedAt without offset",
						"{\"articleCode\":\"X\",\"quantityAvailable\":1,\"changedAt\":\"2026-10-13T10:05:00\"}"),
				Arguments.of("changedAt as epoch number",
						"{\"articleCode\":\"X\",\"quantityAvailable\":1,\"changedAt\":1760349900}"),
				Arguments.of("changedAt after year 9999", "{\"articleCode\":\"X\",\"quantityAvailable\":1,"
						+ "\"changedAt\":\"+999999999-12-31T23:59:59Z\"}"),
				// JSON escape for U+0000 (built from char 92 so the source holds the escape, not the character).
				Arguments.of("articleCode with a NUL character", "{\"articleCode\":\"X" + (char) 92
						+ "u0000Y\",\"quantityAvailable\":1,\"changedAt\":\"2026-10-13T10:05:00Z\"}"),
				Arguments.of("warehouse with a NUL character", "{\"articleCode\":\"X\",\"quantityAvailable\":1,"
						+ "\"warehouse\":\"B" + (char) 92 + "u0000\",\"changedAt\":\"2026-10-13T10:05:00Z\"}"));
	}

	// Breaks if: a validation rule is dropped (each case then reaches the database: 202, or 500 for the long code).
	@ParameterizedTest(name = "{0}")
	@MethodSource("unusableBodies")
	void correctlySignedButUnusableUpdateIsRejectedAndNothingIsStored(String description, String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<String> response = post("erp-400", new HmacVerifier(ERP_WEBHOOK_SECRET).sign(bytes), bytes);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(countRows("erp_inbox_event")).isZero();
		assertThat(countRows("stock_level")).isZero();
	}

	// Breaks if: the clock-skew tolerance is ignored (4 minutes ahead would be rejected) or not enforced (6 minutes
	// ahead would be stored and freeze the SKU until then). The tolerance is 5 minutes.
	@ParameterizedTest(name = "{0} minutes ahead -> {1}")
	@CsvSource({ "4, 202", "6, 400" })
	void changedAtAheadOfOurClockIsOnlyAcceptedWithinTheTolerance(long minutesAhead, int expectedStatus) {
		String changedAt = Instant.now().plus(Duration.ofMinutes(minutesAhead)).truncatedTo(ChronoUnit.SECONDS).toString();
		String body = "{\"articleCode\":\"X\",\"quantityAvailable\":1,\"changedAt\":\"" + changedAt + "\"}";

		ResponseEntity<String> response = postSignedStockUpdate("erp-skew", body);

		assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
		assertThat(countRows("stock_level")).isEqualTo((expectedStatus == 202) ? 1 : 0);
	}

	static Stream<Arguments> badEventIds() {
		return Stream.of(Arguments.of("missing", null), Arguments.of("longer than 200 characters", "e".repeat(201)));
	}

	// Breaks if: the event id check is dropped (missing: NOT NULL violation; too long: varchar(200) overflow; both 500).
	@ParameterizedTest(name = "{0}")
	@MethodSource("badEventIds")
	void correctlySignedUpdateWithABadEventIdIsRejectedAndNothingIsStored(String description, String eventId) {
		byte[] bytes = UPDATE.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<String> response = post(eventId, new HmacVerifier(ERP_WEBHOOK_SECRET).sign(bytes), bytes);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(countRows("erp_inbox_event")).isZero();
	}

	// Breaks if: StockUpdateService.apply() loses @Transactional (the inbox row and the new stock level would stay
	// although the outbox message, the last write, failed: the shop would never hear about this change).
	@Test
	void failureWritingTheOutboxMessageRollsBackTheWholeUpdate() {
		this.jdbc.sql("""
				CREATE FUNCTION reject_outbox() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'rejected by test' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql
				""").update();
		this.jdbc.sql("""
				CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox_message
				FOR EACH ROW EXECUTE FUNCTION reject_outbox()
				""").update();

		ResponseEntity<String> response = postSignedStockUpdate("erp-s8", UPDATE);

		assertThat(response.getStatusCode().is5xxServerError()).isTrue();
		assertThat(countRows("erp_inbox_event")).isZero();
		assertThat(countRows("stock_level")).isZero();
	}

	private ResponseEntity<String> post(String eventId, String signature, byte[] body) {
		return this.http.post()
			.uri("/webhooks/erp/stock")
			.contentType(MediaType.APPLICATION_JSON)
			.headers((headers) -> {
				if (eventId != null) {
					headers.add("X-Erp-Event-Id", eventId);
				}
				if (signature != null) {
					headers.add("X-Erp-Signature", signature);
				}
			})
			.body(body)
			.retrieve()
			.toEntity(String.class);
	}

}
