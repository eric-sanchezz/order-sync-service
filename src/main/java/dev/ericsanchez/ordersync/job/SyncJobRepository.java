package dev.ericsanchez.ordersync.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** JPA for the admin reads; every write and the claim are native SQL in {@link SyncJobQueries}. */
public interface SyncJobRepository extends JpaRepository<SyncJob, Long>, SyncJobQueries {

	Page<SyncJob> findByStatus(JobStatus status, Pageable pageable);

}
