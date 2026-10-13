package dev.ericsanchez.ordersync.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry the admin key in {@code X-API-Key} (constant-time comparison). It only sets the
 * security context; rejecting is left to the authorization rules, so a missing or wrong key ends in a 401.
 * Not a {@code @Component} on purpose: Boot would also register it as a servlet filter and run it twice.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-API-Key";

	private final byte[] expectedKey;

	public ApiKeyAuthFilter(String apiKey) {
		this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String providedKey = request.getHeader(HEADER);
		if (providedKey != null
				&& MessageDigest.isEqual(providedKey.getBytes(StandardCharsets.UTF_8), this.expectedKey)) {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin", null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
			SecurityContextHolder.setContext(context);
		}
		chain.doFilter(request, response);
	}

}
