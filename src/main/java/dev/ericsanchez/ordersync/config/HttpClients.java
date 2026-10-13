package dev.ericsanchez.ordersync.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** RestClient setup shared by the outbound integrations (ERP, shop). */
public final class HttpClients {

	private HttpClients() {
	}

	/**
	 * {@code timeout} bounds both connecting and reading the response. HTTP/1.1 on purpose: the JDK client otherwise
	 * tries an h2c upgrade on every plain-HTTP call.
	 */
	public static RestClient.Builder restClient(URI baseUrl, Duration timeout) {
		HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(timeout)
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(timeout);
		return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(requestFactory);
	}

}
