package dev.ericsanchez.ordersync.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminApiSecurityTest extends IntegrationTest {

	// Breaks if: /admin/** is permitAll, or the 401 entry point is missing (Spring Security would answer 403).
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "wrong-key", "test-admin-key-but-longer" })
	void adminApiRejectsMissingOrWrongKey(String apiKey) {
		ResponseEntity<String> response = this.http.get()
			.uri("/admin/jobs")
			.headers((headers) -> {
				if (apiKey != null) {
					headers.add("X-API-Key", apiKey);
				}
			})
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// Breaks if: the filter never authenticates (every admin call would be 401).
	@Test
	void adminApiAcceptsTheConfiguredKey() {
		ResponseEntity<String> response = this.http.get()
			.uri("/admin/jobs")
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo("{\"items\":[],\"page\":0,\"size\":20,\"totalElements\":0}");
	}

}
