package dev.ericsanchez.ordersync.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.ResponseEntity;

/** Application port, without a key: only the docs and the webhooks are reachable; everything else answers 401. */
class PublicEndpointsProbeTest extends IntegrationTest {

	// Breaks if: anyRequest() becomes permitAll, the entry point is lost (403 instead of 401), or the application port
	// opens /actuator again. (Adapted by the coder in H3: the actuator moved to the management port, see HealthTest,
	// so /actuator/health on the application port is now 401 like any other unknown path.)
	@ParameterizedTest(name = "{0} {1} -> {2}")
	@CsvSource({ "GET,/actuator/health,401", "GET,/actuator/prometheus,401", "GET,/actuator/env,401", "GET,/admin/stats,401",
			"POST,/admin/jobs/1/replay,401", "GET,/nonexistent,401" })
	void anonymousAccess(String method, String path, int expected) {
		ResponseEntity<String> response = this.http.method(org.springframework.http.HttpMethod.valueOf(method))
			.uri(path)
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode().value()).as(path).isEqualTo(expected);
	}

}
