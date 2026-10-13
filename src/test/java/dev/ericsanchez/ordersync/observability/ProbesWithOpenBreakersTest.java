package dev.ericsanchez.ordersync.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * ERP and shop both down (both breakers OPEN). The service is degraded, but it is alive and can still take webhooks:
 * neither liveness (Docker restarts) nor readiness (traffic is cut) may go down because of a downstream system.
 */
class ProbesWithOpenBreakersTest extends IntegrationTest {

	@Autowired
	private CircuitBreakerRegistry circuitBreakers;

	@Autowired
	private JsonMapper json;

	// Breaks if: the erp/shop health indicators are added to the liveness or readiness group, or DEGRADED is
	// mapped to 503 for the probes.
	@Test
	void bothDownstreamsOpenLeaveLivenessAndReadinessUp() {
		this.circuitBreakers.circuitBreaker("erp").transitionToOpenState();
		this.circuitBreakers.circuitBreaker("shop").transitionToOpenState();

		int overall = status("/actuator/health");
		int liveness = status("/actuator/health/liveness");
		int readiness = status("/actuator/health/readiness");
		String readinessBody = this.actuator.get().uri("/actuator/health/readiness").retrieve().body(String.class);

		assertThat(overall).isEqualTo(200);
		assertThat(liveness).isEqualTo(200);
		assertThat(readiness).isEqualTo(200);
		assertThat(this.json.readTree(readinessBody).get("status").asString()).isEqualTo("UP");
	}

	private int status(String path) {
		return this.actuator.get().uri(path).retrieve().toBodilessEntity().getStatusCode().value();
	}

}
