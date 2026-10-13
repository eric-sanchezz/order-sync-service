package dev.ericsanchez.ordersync.outbox;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA for the admin reads; every write and the claim are native SQL in {@link OutboxQueries}. */
public interface OutboxRepository extends JpaRepository<OutboxMessage, Long>, OutboxQueries {

	Page<OutboxMessage> findByStatus(OutboxStatus status, Pageable pageable);

}
