package dev.ericsanchez.ordersync.stock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.ericsanchez.ordersync.observability.SyncMetrics;
import dev.ericsanchez.ordersync.outbox.ClaimedMessage;
import dev.ericsanchez.ordersync.outbox.DeliveryResult;
import dev.ericsanchez.ordersync.outbox.OutboxDestination;
import dev.ericsanchez.ordersync.outbox.OutboxDispatcher;
import dev.ericsanchez.ordersync.outbox.OutboxRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pushes stock levels to the shop: {@code PUT /wp-json/wc/v3/products/by-sku/{sku}/stock}. Responses are classified
 * like the ERP's (2xx delivered; 408, 429, 5xx, timeout, IO retryable; other 4xx permanent), and the {@code shop}
 * circuit breaker only counts retryable failures.
 * <p>
 * Before sending, it checks that the message is still the newest one for its SKU. Outbox ids follow the order in
 * which stock levels were applied (the webhook holds the stock_level row lock until it commits), so a newer id means
 * newer stock, even when both changes carry the same changedAt. A replayed DEAD message would otherwise overwrite a
 * newer quantity in the shop.
 */
@Component
public class ShopStockDispatcher implements OutboxDispatcher {

	static final String STOCK_PATH = "/wp-json/wc/v3/products/by-sku/{sku}/stock";

	private static final int MAX_ERROR_BODY_LENGTH = 1000;

	private final RestClient restClient;

	private final CircuitBreaker circuitBreaker;

	private final OutboxRepository outbox;

	private final JsonMapper jsonMapper;

	private final SyncMetrics metrics;

	public ShopStockDispatcher(@Qualifier("shopRestClient") RestClient shopRestClient,
			@Qualifier("shopCircuitBreaker") CircuitBreaker shopCircuitBreaker, OutboxRepository outbox,
			JsonMapper jsonMapper, SyncMetrics metrics) {
		this.restClient = shopRestClient;
		this.circuitBreaker = shopCircuitBreaker;
		this.outbox = outbox;
		this.jsonMapper = jsonMapper;
		this.metrics = metrics;
	}

	/** Body of the shop's stock endpoint. */
	record StockQuantity(@JsonProperty("stock_quantity") long stockQuantity) {
	}

	/** Thrown inside the breaker so that only retryable failures are recorded; never leaves this class. */
	private static final class RetryableFailure extends RuntimeException {

		private final DeliveryResult.Retryable result;

		RetryableFailure(DeliveryResult.Retryable result) {
			super(result.error(), null, false, false);
			this.result = result;
		}

	}

	/** Circuit breaker predicate: a 4xx means the shop is alive, so only retryable failures count. */
	public static boolean isRecordedFailure(Throwable throwable) {
		return throwable instanceof RetryableFailure;
	}

	@Override
	public OutboxDestination destination() {
		return OutboxDestination.SHOP_STOCK_API;
	}

	@Override
	public DeliveryResult deliver(ClaimedMessage message) {
		StockLevelChanged change;
		try {
			change = this.jsonMapper.readValue(message.payload(), StockLevelChanged.class);
		}
		catch (JacksonException unreadable) {
			return new DeliveryResult.Permanent("unreadable outbox payload: " + unreadable.getOriginalMessage());
		}
		if (this.outbox.hasNewerMessage(message)) {
			return new DeliveryResult.Superseded("a newer stock message exists for " + change.articleCode());
		}
		long start = System.nanoTime();
		DeliveryResult result = putThroughCircuitBreaker(message.id(), change);
		this.metrics.recordShopRequest(outcomeTag(result), System.nanoTime() - start);
		return result;
	}

	private static String outcomeTag(DeliveryResult result) {
		return switch (result) {
			case DeliveryResult.Delivered delivered -> "delivered";
			case DeliveryResult.Retryable retryable -> "retryable";
			case DeliveryResult.CircuitOpen open -> "circuit_open";
			case DeliveryResult.Permanent permanent -> "permanent";
			case DeliveryResult.Superseded superseded -> "superseded";
		};
	}

	private DeliveryResult putThroughCircuitBreaker(long messageId, StockLevelChanged change) {
		try {
			return this.circuitBreaker.executeSupplier(() -> {
				DeliveryResult result = put(messageId, change);
				if (result instanceof DeliveryResult.Retryable retryable) {
					throw new RetryableFailure(retryable);
				}
				return result;
			});
		}
		catch (CallNotPermittedException open) {
			return new DeliveryResult.CircuitOpen();
		}
		catch (RetryableFailure failure) {
			return failure.result;
		}
	}

	/** {@code outbox-<id>} as Idempotency-Key: a retry of the same message is recognisable as such by the shop. */
	private DeliveryResult put(long messageId, StockLevelChanged change) {
		try {
			return this.restClient.put()
				.uri(STOCK_PATH, change.articleCode())
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", "outbox-" + messageId)
				.body(new StockQuantity(change.quantityAvailable()))
				.exchange((request, response) -> classify(response));
		}
		catch (RestClientException ex) {
			// Timeouts and connection errors arrive as ResourceAccessException.
			return new DeliveryResult.Retryable("IO: " + ex.getMessage());
		}
	}

	private static DeliveryResult classify(ClientHttpResponse response) throws IOException {
		HttpStatusCode status = response.getStatusCode();
		if (status.is2xxSuccessful()) {
			return new DeliveryResult.Delivered();
		}
		String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
		String error = "HTTP " + status.value() + ": "
				+ ((body.length() <= MAX_ERROR_BODY_LENGTH) ? body : body.substring(0, MAX_ERROR_BODY_LENGTH));
		int code = status.value();
		if (code == 408 || code == 429 || status.is5xxServerError()) {
			return new DeliveryResult.Retryable(error);
		}
		return new DeliveryResult.Permanent(error);
	}

}
