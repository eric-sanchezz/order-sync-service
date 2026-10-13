package dev.ericsanchez.ordersync.webhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * What the webhook accepts must be what the worker can process. Bodies that only the webhook understands (BOM,
 * trailing bytes) must be rejected at the door with 400, never accepted and then lost, and never answered with 500.
 */
class WebhookBodyEncodingTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the BOM is stored with the body (202, then the worker cannot parse it and the job goes DEAD).
	@Test
	void utf8BodyWithBomIsEitherSyncedOrRejectedUpFront() throws Exception {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)
			.withBody("{\"orderId\":\"ERP-BOM\"}")));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
		body.write(ORDER.getBytes(StandardCharsets.UTF_8));

		ResponseEntity<String> response = postSignedBytes("evt-bom", body.toByteArray());
		this.worker.pollOnce();

		if (response.getStatusCode() == HttpStatus.BAD_REQUEST) {
			assertThat(countRows("sync_job")).isZero();
		}
		else {
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
			assertThat(jobRow(1)).containsEntry("status", "DONE");
		}
	}

	// Breaks if: the body is stored before it is parsed, or Jackson stops failing on trailing tokens (the raw 0x00
	// reaches the text column and the answer is 500).
	@Test
	void validJsonFollowedByANulByteIsABadRequestAndNothingIsStored() throws Exception {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(ORDER.getBytes(StandardCharsets.UTF_8));
		body.write(0x00);

		ResponseEntity<String> response = postSignedBytes("evt-trailing-nul", body.toByteArray());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("inbox_event")).isZero();
	}

	// Breaks if: the body is parsed from raw bytes (Jackson auto-detects UTF-16) but stored as UTF-8 (500).
	@Test
	void utf16BodyIsABadRequestAndNothingIsStored() {
		byte[] utf16 = ORDER.getBytes(StandardCharsets.UTF_16LE);

		ResponseEntity<String> response = postSignedBytes("evt-utf16", utf16);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("inbox_event")).isZero();
	}

	private ResponseEntity<String> postSignedBytes(String eventId, byte[] bytes) {
		return this.http.post()
			.uri("/webhooks/shop/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Shop-Event-Id", eventId)
			.header("X-Shop-Signature", new HmacVerifier(WEBHOOK_SECRET).sign(bytes))
			.body(bytes)
			.retrieve()
			.toEntity(String.class);
	}

}
