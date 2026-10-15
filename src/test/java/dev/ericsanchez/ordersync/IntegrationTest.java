package dev.ericsanchez.ordersync;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.ericsanchez.ordersync.job.SyncWorker;
import dev.ericsanchez.ordersync.outbox.OutboxRelay;
import dev.ericsanchez.ordersync.webhook.HmacVerifier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * Shared infrastructure only: the running app on a random port, real Postgres, the WireMock ERP and shop, and a clean slate
 * before every test (data tables truncated, WireMock reset, every circuit breaker closed). All test classes share
 * one Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot tests disable metrics export by default; the Prometheus endpoint is part of what is tested here.
@AutoConfigureMetrics
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

	/** Same values as application-test.properties. */
	protected static final String WEBHOOK_SECRET = "test-webhook-secret";

	protected static final String ADMIN_KEY = "test-admin-key";

	protected static final String ERP_WEBHOOK_SECRET = "test-erp-webhook-secret";

	protected static final String SALES_ORDERS = "/api/v1/sales-orders";

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	@Qualifier("erpMockContainer")
	private GenericContainer<?> erpMockContainer;

	@Autowired
	@Qualifier("shopMockContainer")
	private GenericContainer<?> shopMockContainer;

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected SyncWorker worker;

	@Autowired
	protected OutboxRelay relay;

	/** Client for the WireMock ERP: stubs and the request journal. */
	protected WireMock erp;

	/** Client for the WireMock shop (stock API): stubs and the request journal. */
	protected WireMock shop;

	/** Client for the app. Never throws on 4xx/5xx, so tests can assert on the status. */
	protected RestClient http;

	/** Client for the management port (actuator). Never throws on 4xx/5xx. */
	protected RestClient actuator;

	@BeforeEach
	void resetSharedState() {
		this.jdbc.sql("""
				TRUNCATE sync_job, inbox_event, outbox_message, erp_inbox_event, stock_level, event_sequence
				RESTART IDENTITY
				""")
			.update();
		this.erp = new WireMock(this.erpMockContainer.getHost(),
				this.erpMockContainer.getMappedPort(TestcontainersConfiguration.WIREMOCK_PORT));
		this.erp.resetMappings();
		this.erp.resetRequests();
		this.erp.resetScenarios();
		this.shop = new WireMock(this.shopMockContainer.getHost(),
				this.shopMockContainer.getMappedPort(TestcontainersConfiguration.WIREMOCK_PORT));
		this.shop.resetMappings();
		this.shop.resetRequests();
		this.shop.resetScenarios();
		this.circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
		this.http = RestClient.builder()
			.baseUrl("http://localhost:" + this.port)
			.defaultStatusHandler((status) -> true, (request, response) -> {
			})
			.build();
		this.actuator = RestClient.builder()
			.baseUrl("http://localhost:" + this.managementPort)
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

	/** Signs {@code body} with the ERP's test secret and posts it to the stock webhook. */
	protected ResponseEntity<String> postSignedStockUpdate(String eventId, String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		return this.http.post()
			.uri("/webhooks/erp/stock")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Erp-Event-Id", eventId)
			.header("X-Erp-Signature", new HmacVerifier(ERP_WEBHOOK_SECRET).sign(bytes))
			.body(bytes)
			.retrieve()
			.toEntity(String.class);
	}

	/**
	 * Sum of the samples of {@code name} in /actuator/prometheus whose labels include all of {@code labels} (each
	 * written as in the exposition format, e.g. {@code outcome="success"}). 0 if there is none.
	 */
	protected double metric(String name, String... labels) {
		String scrape = this.actuator.get().uri("/actuator/prometheus").retrieve().body(String.class);
		return scrape.lines()
			.filter((line) -> line.startsWith(name + "{") || line.startsWith(name + " "))
			.filter((line) -> Arrays.stream(labels).allMatch(line::contains))
			.mapToDouble((line) -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
			.sum();
	}

	protected Map<String, Object> outboxRow(long messageId) {
		return this.jdbc.sql("SELECT status, attempts, last_error, delivered_at FROM outbox_message WHERE id = :id")
			.param("id", messageId)
			.query()
			.singleRow();
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
