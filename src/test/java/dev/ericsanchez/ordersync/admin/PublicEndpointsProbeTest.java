package dev.ericsanchez.ordersync.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.ResponseEntity;

/** Without a key, only health, the docs and the webhook are reachable; everything else answers 401. */
class PublicEndpointsProbeTest extends IntegrationTest {

	// Breaks if: /actuator/health/** stops matching /actuator/health, anyRequest() becomes permitAll, or the entry
	// point is lost (403 instead of 401).
	@ParameterizedTest(name = "{0} {1} -> {2}")
	@CsvSource({ "GET,/actuator/health,200", "GET,/actuator/env,401", "GET,/admin/stats,401",
			"POST,/admin/jobs/1/replay,401", "GET,/nonexistent,401" })
	void anonymousAccess(String method, String path, int expected) {
		ResponseEntity<String> response = this.http.method(org.springframework.http.HttpMethod.valueOf(method))
			.uri(path)
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode().value()).as(path).isEqualTo(expected);
	}

}
