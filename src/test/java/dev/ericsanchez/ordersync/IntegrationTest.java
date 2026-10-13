package dev.ericsanchez.ordersync;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.ericsanchez.ordersync.job.SyncWorker;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Shared infrastructure only: the running app on a random port, real Postgres, the WireMock ERP, and a clean slate
 * before every test (data tables truncated, WireMock reset, every circuit breaker closed). All test classes share
 * one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	/** Same values as application-test.properties. */
	protected static final String WEBHOOK_SECRET = "test-webhook-secret";

	protected static final String ADMIN_KEY = "test-admin-key";

	protected static final String SALES_ORDERS = "/api/v1/sales-orders";

	@LocalServerPort
	private int port;

	@Autowired
	private GenericContainer<?> erpMockContainer;

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected SyncWorker worker;

	/** Client for the WireMock ERP: stubs and the request journal. */
	protected WireMock erp;

	/** Client for the app. Never throws on 4xx/5xx, so tests can assert on the status. */
	protected RestClient http;

	@BeforeEach
	void resetSharedState() {
		this.jdbc.sql("TRUNCATE sync_job, inbox_event RESTART IDENTITY").update();
		this.erp = new WireMock(this.erpMockContainer.getHost(),
				this.erpMockContainer.getMappedPort(TestcontainersConfiguration.WIREMOCK_PORT));
		this.erp.resetMappings();
		this.erp.resetRequests();
		this.erp.resetScenarios();
		this.circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
		this.http = RestClient.builder()
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler((status) -> true, (request, response) -> {
			})
			.build();
	}

	/** Signs {@code body} with the test secret, as the shop does, and posts it to the webhook. */
	protected ResponseEntity<String> postSignedOrder(String eventId, String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		return this.http.post()
			.uri("/webhooks/shop/orders")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Shop-Event-Id", eventId)
			.header("X-Shop-Signature", new HmacVerifier(WEBHOOK_SECRET).sign(bytes))
			.body(bytes)
			.retrieve()
			.toEntity(String.class);
	}

	protected Map<String, Object> jobRow(long jobId) {
		return this.jdbc.sql("SELECT status, attempts, erp_order_id, last_error, completed_at FROM sync_job WHERE id = :id")
			.param("id", jobId)
			.query()
			.singleRow();
	}

	protected long countRows(String table) {
		return this.jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
	}

}
