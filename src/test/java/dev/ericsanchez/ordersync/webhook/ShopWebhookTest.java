package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The webhook's gatekeeping: signature, event id, readable JSON, and redelivery of the same event. */
class ShopWebhookTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	static Stream<Arguments> badSignatures() {
		HmacVerifier shop = new HmacVerifier(WEBHOOK_SECRET);
		byte[] reformattedBody = (ORDER + " ").getBytes(StandardCharsets.UTF_8);
		return Stream.of(
				Arguments.of("missing", null),
				Arguments.of("signed with another secret", new HmacVerifier("not-the-secret").sign(bytes(ORDER))),
				Arguments.of("signature of a body with one extra space", shop.sign(reformattedBody)));
	}

	// Breaks if: HmacVerifier.verify() always returns true, or the HMAC is computed on a re-serialized body.
	@ParameterizedTest(name = "{0}")
	@MethodSource("badSignatures")
	void badSignatureIsRejectedAndNothingIsStored(String description, String signature) {
		ResponseEntity<String> response = this.http.post()
			.uri("/webhooks/shop/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Shop-Event-Id", "evt-t2")
			.headers((headers) -> {
				if (signature != null) {
					headers.add("X-Shop-Signature", signature);
				}
			})
			.body(bytes(ORDER))
			.retrieve()
			.toEntity(String.class);
		this.worker.pollOnce();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(countRows("inbox_event")).isZero();
		assertThat(countRows("sync_job")).isZero();
		this.erp.verifyThat(0, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	static Stream<Arguments> badRequests() {
		return Stream.of(
				Arguments.of("unreadable JSON", "evt-t11", "{ this is not json"),
				Arguments.of("missing event id", null, ORDER),
				Arguments.of("event id longer than 200 characters", "e".repeat(201), ORDER));
	}

	// Breaks if: the payload is stored before it is parsed, or the event id length is left to the database (500).
	@ParameterizedTest(name = "{0}")
	@MethodSource("badRequests")
	void correctlySignedButUnusableRequestIsRejectedAndNothingIsStored(String description, String eventId,
			String body) {
		ResponseEntity<String> response = this.http.post()
			.uri("/webhooks/shop/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.headers((headers) -> {
				if (eventId != null) {
					headers.add("X-Shop-Event-Id", eventId);
				}
			})
			.header("X-Shop-Signature", new HmacVerifier(WEBHOOK_SECRET).sign(bytes(body)))
			.body(bytes(body))
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("inbox_event")).isZero();
		assertThat(countRows("sync_job")).isZero();
	}

	// Breaks if: the inbox insert loses its ON CONFLICT (the second delivery would fail or create a second job).
	@Test
	void sameEventDeliveredTwiceCreatesOneJobAndOneErpOrder() {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-3\"}")));

		ResponseEntity<String> first = postSignedOrder("evt-t3", ORDER);
		ResponseEntity<String> second = postSignedOrder("evt-t3", ORDER);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(first.getBody()).isEqualTo("{\"jobId\":1,\"status\":\"PENDING\"}");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody()).isEqualTo("{\"jobId\":1,\"duplicate\":true}");
		assertThat(countRows("sync_job")).isOne();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(jobRow(1)).containsEntry("status", "DONE");
		});
		this.erp.verifyThat(1, postRequestedFor(urlEqualTo(SALES_ORDERS)));
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

}
