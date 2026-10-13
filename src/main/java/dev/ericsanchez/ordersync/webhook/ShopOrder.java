package dev.ericsanchez.ordersync.webhook;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Order as the shop sends it (WooCommerce-like). Every field is nullable on purpose: the webhook only rejects JSON it
 * cannot read, and the transformer turns missing fields into a DEAD job with a clear error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopOrder(
		Long id,
		String number,
		String currency,
		String total,
		Customer customer,
		@JsonProperty("line_items") List<LineItem> lineItems) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Customer(Long id, String email, String company) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LineItem(String sku, String name, Integer quantity, String price) {
	}

}
