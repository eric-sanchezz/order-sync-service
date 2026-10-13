package dev.ericsanchez.ordersync.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The correlation fields come from the MDC, not from the message text. The test log pattern prints them as
 * {@code [eventId=... jobId=... outboxId=...]} (application-test.properties); in docker-compose they are ECS JSON fields.
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationLoggingTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "currency": "EUR", "total": "12.50", "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "quantity": 1, "price": "12.50" } ] }
			""";

	// Breaks if: the worker sets the MDC outside the job's virtual thread (the log line would have an empty eventId)
	// or stops setting it.
	@Test
	void workerLogLinesCarryTheEventAndJobIds(CapturedOutput output) {
		this.erp.register(post(urlEqualTo(SALES_ORDERS)).willReturn(aResponse().withStatus(201)));
		postSignedOrder("evt-o3", ORDER);

		this.worker.pollOnce();

		assertThat(output.getOut().lines().filter((line) -> line.contains("SyncWorker") && line.contains("DONE")))
			.singleElement()
			.asString()
			.contains("[eventId=evt-o3 jobId=1 outboxId=]");
	}

	// Breaks if: the webhook controller does not put the event id in the MDC.
	@Test
	void webhookLogLinesCarryTheEventId(CapturedOutput output) {
		postSignedOrder("evt-o3-webhook", ORDER);

		assertThat(output.getOut().lines().filter((line) -> line.contains("Accepted shop event")))
			.singleElement()
			.asString()
			.contains("[eventId=evt-o3-webhook ");
	}

}
