package dev.ericsanchez.ordersync.job;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Shop customer id -> ERP customer code, seeded by Flyway. No default: an unknown customer sends the job to DEAD. */
@Repository
public class CustomerMappingRepository {

	private final JdbcClient jdbc;

	public CustomerMappingRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<String> findErpCustomerCode(long shopCustomerId) {
		return this.jdbc.sql("SELECT erp_customer_code FROM customer_mapping WHERE shop_customer_id = :id")
			.param("id", shopCustomerId)
			.query(String.class)
			.optional();
	}

}
