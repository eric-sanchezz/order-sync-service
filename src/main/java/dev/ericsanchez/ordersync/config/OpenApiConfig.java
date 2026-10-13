package dev.ericsanchez.ordersync.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String API_KEY_SCHEME = "X-API-Key";

	@Bean
	OpenAPI orderSyncOpenApi() {
		return new OpenAPI()
			.info(new Info().title("order-sync-service")
				.description("Shop -> ERP order sync. Admin endpoints need the X-API-Key header; "
						+ "the shop webhook is authenticated with an HMAC signature instead.")
				.version("v1"))
			.components(new Components().addSecuritySchemes(API_KEY_SCHEME,
					new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.HEADER)
						.name(API_KEY_SCHEME)))
			.addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
	}

}
