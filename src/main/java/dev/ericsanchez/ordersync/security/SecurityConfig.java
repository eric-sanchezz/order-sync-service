package dev.ericsanchez.ordersync.security;

import dev.ericsanchez.ordersync.config.OrderSyncProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API security. The shop webhook is public at this layer because it checks its own HMAC signature; the
 * admin API needs the API key; everything else is denied. CSRF is off (no browser sessions), otherwise the webhook POST
 * would get a 403 instead of reaching the signature check.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, OrderSyncProperties properties) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests((requests) -> requests
				.requestMatchers("/webhooks/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
						"/actuator/health/**", "/actuator/prometheus", "/error")
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
