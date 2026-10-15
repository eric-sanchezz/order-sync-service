package dev.ericsanchez.ordersync.security;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API security, three filter chains. On the management port (actuator), health and the Prometheus scrape
 * are open to the internal network and everything else is denied. The static operator page (/ops/**) is public, since it
 * holds no data (the data comes from the admin API with the key the operator types), and is served with a strict
 * Content-Security-Policy that only this chain sets. On the application port, the webhooks are public at
 * this layer because they check their own HMAC signature, the admin API needs the API key, and everything else is
 * denied, including any /actuator path, which does not exist there. CSRF is off (no browser sessions), otherwise the webhook POST
 * would get a 403 instead of reaching the signature check.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	private static final String OPERATOR_PAGE_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
			+ "connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

	/** Matches only requests on the management server (EndpointRequest knows which context serves the actuator). */
	@Bean
	@Order(1)
	SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher(EndpointRequest.toAnyEndpoint())
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests((requests) -> requests.requestMatchers(EndpointRequest.to("health", "prometheus"))
				.permitAll()
				.anyRequest()
				.denyAll())
			.exceptionHandling((exceptions) -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
		return http.build();
	}

	/** The operator page: static files only, no inline script or style allowed, never framed. */
	@Bean
	@Order(2)
	SecurityFilterChain operatorPageSecurityFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/ops/**")
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
			.headers((headers) -> headers.contentSecurityPolicy((csp) -> csp.policyDirectives(OPERATOR_PAGE_CSP)));
		return http.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain securityFilterChain(HttpSecurity http, OrderSyncProperties properties) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/webhooks/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/error")
				.permitAll()
				.requestMatchers("/admin/**")
				.authenticated()
				.anyRequest()
				.denyAll())
			.exceptionHandling((exceptions) -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.addFilterBefore(new ApiKeyAuthFilter(properties.admin().apiKey()),
					UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

}
