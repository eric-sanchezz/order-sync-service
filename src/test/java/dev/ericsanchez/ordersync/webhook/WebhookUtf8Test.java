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

/** The webhook only takes UTF-8, and it stores exactly the text it checked. */
class WebhookUtf8Test extends IntegrationTest {

	// Breaks if: the body is decoded leniently (new String(bytes, UTF_8) turns 0xFF into U+FFFD, the JSON is still
	// valid, and a corrupted SKU is accepted with 202).
	@Test
	void malformedUtf8IsABadRequestAndNothingIsStored() throws Exception {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write("""
				{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
				  "line_items": [ { "sku": "TORN-""".getBytes(StandardCharsets.UTF_8));
		body.write(0xFF);
		body.write("\", \"quantity\": 1, \"price\": \"12.50\" } ] }".getBytes(StandardCharsets.UTF_8));

		ResponseEntity<String> response = postSignedBytes("evt-bad-utf8", body.toByteArray());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("inbox_event")).isZero();
	}

	// Breaks if: the leading BOM is not stripped before parsing and storing (the order would be rejected with 400,
	// although Notepad and PowerShell write UTF-8 files exactly like this).
	@Test
	void utf8BodyWithBomIsAcceptedAndSynced() throws Exception {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-BOM\"}")));
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
		body.write("""
				{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
				  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
				""".getBytes(StandardCharsets.UTF_8));

		ResponseEntity<String> response = postSignedBytes("evt-bom-accepted", body.toByteArray());
		this.worker.pollOnce();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(jobRow(1)).containsEntry("status", "DONE").containsEntry("erp_order_id", "ERP-BOM");
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
