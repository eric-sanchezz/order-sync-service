package dev.ericsanchez.ordersync;

import org.springframework.boot.SpringApplication;

public class TestOrderSyncServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(OrderSyncServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
