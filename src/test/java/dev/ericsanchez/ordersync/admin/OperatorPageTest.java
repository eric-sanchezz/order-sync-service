package dev.ericsanchez.ordersync.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The static operator page under /ops/**: public without a key (it holds no data; the data comes from /admin/** with
 * the key the operator types), with a strict Content-Security-Policy. Its JavaScript is not tested here (see the
 * README): only what the server serves.
 */
class OperatorPageTest extends IntegrationTest {

	// Breaks if: the permitAll of /ops/** is lost (401), index.html is moved or renamed (404), or the key field stops
	// being a password field.
	@Test
	void pageIsServedWithoutAKey() {
		ResponseEntity<String> response = this.http.get().uri("/ops/index.html").retrieve().toEntity(String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
		assertThat(response.getBody()).contains("id=\"api-key\"").contains("type=\"password\"");
	}

	// Breaks if: the headers configuration of the /ops/** chain is removed, or someone adds 'unsafe-inline'.
	@Test
	void pageCarriesAStrictContentSecurityPolicy() {
		ResponseEntity<String> response = this.http.get().uri("/ops/index.html").retrieve().toEntity(String.class);

		String csp = response.getHeaders().getFirst("Content-Security-Policy");
		assertThat(csp).isNotNull()
			.contains("script-src 'self'")
			.contains("frame-ancestors 'none'")
			.doesNotContain("unsafe-inline")
			.doesNotContain("unsafe-eval");
	}

	// The page's CSP (script-src 'self', no inline) would break swagger-ui, which needs inline code, so it must stay
	// on the /ops/** chain only. Breaks if: the CSP is set on the general chain, or the /ops/** matcher is widened so
	// that it also takes /swagger-ui/** (for example to /**). (Added by the tester in H7.)
	@Test
	void apiDocsKeepWorkingWithoutThePageCsp() {
		ResponseEntity<String> swaggerUi = this.http.get()
			.uri("/swagger-ui/index.html")
			.retrieve()
			.toEntity(String.class);
		ResponseEntity<String> apiDocs = this.http.get().uri("/v3/api-docs").retrieve().toEntity(String.class);

		assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(swaggerUi.getHeaders().getFirst("Content-Security-Policy")).isNull();
		assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(apiDocs.getBody()).contains("/admin/jobs");
		assertThat(apiDocs.getHeaders().getFirst("Content-Security-Policy")).isNull();
	}

	// A static check of what is served, not of behaviour: the JavaScript has no tests in CI and the CSP would block
	// inline code silently. Breaks if: the demo key is pasted into the JS, an inline <script> or an onclick= is added,
	// or ops.js renders with innerHTML.
	@Test
	void servedFilesHoldNoKeyAndNoInlineScript() {
		String html = this.http.get().uri("/ops/index.html").retrieve().body(String.class);
		String js = this.http.get().uri("/ops/ops.js").retrieve().body(String.class);
		String css = this.http.get().uri("/ops/ops.css").retrieve().body(String.class);

		assertThat(html + js + css).doesNotContain(ADMIN_KEY).doesNotContain("dev-admin-key");
		assertThat(html).doesNotContainPattern("<script(?![^>]*\\bsrc=)")
			.doesNotContainPattern("\\son[a-z]+=")
			.doesNotContain("style=");
		assertThat(js).doesNotContain("innerHTML")
			.doesNotContain("insertAdjacentHTML")
			.doesNotContain("outerHTML")
			.doesNotContain("document.write");
	}

}
