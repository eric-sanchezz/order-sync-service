package dev.ericsanchez.ordersync.erp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.ericsanchez.ordersync.transform.ErpOrder;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Creates sales orders in the ERP. Never throws for HTTP or IO problems: every attempt ends in an {@link ErpResult}.
 * The shop event id goes out as {@code Idempotency-Key}, the second idempotency layer, so a retry after a timeout
 * does not create the order twice.
 */
@Component
public class ErpClient {

	static final String SALES_ORDERS_PATH = "/api/v1/sales-orders";

	private static final int MAX_ERROR_BODY_LENGTH = 1000;

	private final RestClient restClient;

	private final CircuitBreaker circuitBreaker;

	private final JsonMapper jsonMapper;

	public ErpClient(RestClient erpRestClient, CircuitBreaker erpCircuitBreaker, JsonMapper jsonMapper) {
		this.restClient = erpRestClient;
		this.circuitBreaker = erpCircuitBreaker;
		this.jsonMapper = jsonMapper;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record CreatedOrder(String orderId) {
	}

	/** Thrown inside the breaker so that only retryable failures are recorded; never leaves this class. */
	private static final class RetryableFailure extends RuntimeException {

		private final ErpResult.Retryable result;

		RetryableFailure(ErpResult.Retryable result) {
			super(result.error(), null, false, false);
			this.result = result;
		}

	}

	/** Circuit breaker predicate: a 4xx means the ERP is alive, so only retryable failures count. */
	public static boolean isRecordedFailure(Throwable throwable) {
		return throwable instanceof RetryableFailure;
	}

	public ErpResult createSalesOrder(String eventId, ErpOrder order) {
		try {
			return this.circuitBreaker.executeSupplier(() -> {
				ErpResult result = post(eventId, order);
				if (result instanceof ErpResult.Retryable retryable) {
					throw new RetryableFailure(retryable);
				}
				return result;
			});
		}
		catch (CallNotPermittedException open) {
			return new ErpResult.CircuitOpen();
		}
		catch (RetryableFailure failure) {
			return failure.result;
		}
	}

	private ErpResult post(String eventId, ErpOrder order) {
		try {
			return this.restClient.post()
				.uri(SALES_ORDERS_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", eventId)
				.header("X-Correlation-Id", eventId)
				.body(order)
				.exchange((request, response) -> classify(response));
		}
		catch (RestClientException ex) {
			// Timeouts and connection errors arrive as ResourceAccessException.
			return new ErpResult.Retryable("IO: " + ex.getMessage());
		}
	}

	private ErpResult classify(ClientHttpResponse response) throws IOException {
		HttpStatusCode status = response.getStatusCode();
		String body = readBody(response.getBody());
		if (status.is2xxSuccessful()) {
			return new ErpResult.Success(orderIdFrom(body));
		}
		String error = "HTTP " + status.value() + ": " + truncate(body);
		int code = status.value();
		if (code == 408 || code == 429 || status.is5xxServerError()) {
			return new ErpResult.Retryable(error);
		}
		return new ErpResult.Permanent(error);
	}

	/** The order was accepted even if the body is odd; a missing id is stored as null rather than retried. */
	private String orderIdFrom(String body) {
		if (body.isBlank()) {
			return null;
		}
		try {
			CreatedOrder created = this.jsonMapper.readValue(body, CreatedOrder.class);
			return (created != null) ? created.orderId() : null;
		}
		catch (JacksonException unreadable) {
			return null;
		}
	}

	private static String readBody(InputStream body) throws IOException {
		return new String(body.readAllBytes(), StandardCharsets.UTF_8);
	}

	private static String truncate(String text) {
		return (text.length() <= MAX_ERROR_BODY_LENGTH) ? text : text.substring(0, MAX_ERROR_BODY_LENGTH);
	}

}
