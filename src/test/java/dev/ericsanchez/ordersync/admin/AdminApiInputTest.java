package dev.ericsanchez.ordersync.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Paging parameters outside what the API supports are a client error, never a server error. */
class AdminApiInputTest extends IntegrationTest {

	// Breaks if: @Min(1) or @Max(100) on size, or @Min(0) on page, stop being enforced (each case is its own bound:
	// without @Max the 101 case answers 200; without @Min PageRequest throws and the answer is 500).
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "size=0", "size=101", "page=-1" })
	void pagingOutsideTheDeclaredBoundsIsABadRequest(String query) {
		ResponseEntity<String> response = this.http.get()
			.uri("/admin/jobs?" + query)
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// Breaks if: @Max(MAX_PAGE) on page is removed (page * size exceeds Integer.MAX_VALUE in Spring Data -> 500).
	@Test
	void pageNumberWhoseOffsetOverflowsAnIntIsNotAServerError() {
		ResponseEntity<String> response = this.http.get()
			.uri("/admin/jobs?page=2147483647&size=100")
			.header("X-API-Key", ADMIN_KEY)
			.retrieve()
			.toEntity(String.class);

		assertThat(response.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.OK);
	}

}
