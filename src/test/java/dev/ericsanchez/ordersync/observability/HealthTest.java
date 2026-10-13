package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class HealthTest extends IntegrationTest {

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	private JsonMapper json;

	// Breaks if: only OPEN/HALF_OPEN are mapped to DEGRADED (FORCED_OPEN would report UP), or DEGRADED gets mapped to
	// an error status (a degraded ERP would make the whole service look down). Boot already maps unknown statuses to
	// 200; the explicit http-mapping in application.properties documents it.
	@Test
	void forcedOpenErpBreakerIsDegradedButTheServiceStaysUp() {
		this.circuitBreakers.circuitBreaker("erp").transitionToForcedOpenState();

		ResponseEntity<String> health = this.actuator.get().uri("/actuator/health").retrieve().toEntity(String.class);
		ResponseEntity<String> liveness = this.actuator.get()
			.uri("/actuator/health/liveness")
			.retrieve()
			.toEntity(String.class);

		JsonNode body = this.json.readTree(health.getBody());
		assertThat(health.getStatusCode().value()).isEqualTo(200);
		assertThat(body.get("status").asString()).isEqualTo("DEGRADED");
		assertThat(body.get("components").get("erp").get("status").asString()).isEqualTo("DEGRADED");
		assertThat(body.get("components").get("erp").get("details").get("state").asString()).isEqualTo("FORCED_OPEN");
		assertThat(body.get("components").get("shop").get("status").asString()).isEqualTo("UP");
		assertThat(liveness.getStatusCode().value()).isEqualTo(200);
		assertThat(this.json.readTree(liveness.getBody()).get("status").asString()).isEqualTo("UP");
	}

	// Breaks if: the management port serves more than health and prometheus. Two independent layers keep it that
	// way, the exposure list and the management security chain; this test fails when both are opened (each one alone
	// is still caught by the other). That the application port has no actuator at all is PublicEndpointsProbeTest's
	// job.
	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({ "/actuator/health, 200", "/actuator/prometheus, 200", "/actuator/env, 401",
			"/actuator/metrics, 401" })
	void managementPortOnlyOpensHealthAndPrometheus(String path, int expectedStatus) {
		int status = this.actuator.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();

		assertThat(status).isEqualTo(expectedStatus);
	}

}
