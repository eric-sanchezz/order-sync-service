package dev.ericsanchez.ordersync;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** The shared containers plus a real Kafka broker (KRaft, one node), the same image as docker-compose. */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestcontainersConfiguration.class)
public class KafkaTestcontainersConfiguration {

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
	}

}
