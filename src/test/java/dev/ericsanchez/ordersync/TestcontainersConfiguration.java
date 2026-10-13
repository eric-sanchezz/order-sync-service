package dev.ericsanchez.ordersync;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Postgres and the same WireMock image docker-compose uses as the ERP and as the shop. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final int WIREMOCK_PORT = 8080;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

	@Bean
	GenericContainer<?> erpMockContainer() {
		return new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.1"))
			.withExposedPorts(WIREMOCK_PORT)
			.waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));
	}

	@Bean
	DynamicPropertyRegistrar erpBaseUrl(GenericContainer<?> erpMockContainer) {
		return (registry) -> registry.add("ordersync.erp.base-url",
				() -> "http://" + erpMockContainer.getHost() + ":" + erpMockContainer.getMappedPort(WIREMOCK_PORT));
	}

	@Bean
	GenericContainer<?> shopMockContainer() {
		return new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.1"))
			.withExposedPorts(WIREMOCK_PORT)
			.waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));
	}

	@Bean
	DynamicPropertyRegistrar shopBaseUrl(GenericContainer<?> shopMockContainer) {
		return (registry) -> registry.add("ordersync.shop.base-url",
				() -> "http://" + shopMockContainer.getHost() + ":" + shopMockContainer.getMappedPort(WIREMOCK_PORT));
	}

}
