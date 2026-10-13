package dev.ericsanchez.ordersync.admin;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import dev.ericsanchez.ordersync.job.JobStatus;
import dev.ericsanchez.ordersync.job.SyncJob;
import dev.ericsanchez.ordersync.job.SyncJobRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Read and replay operations behind the admin API, so the controller never touches a repository. */
@Service
public class AdminJobService {

	private final SyncJobRepository jobs;

	private final Clock clock;

	public AdminJobService(SyncJobRepository jobs, Clock clock) {
		this.jobs = jobs;
		this.clock = clock;
	}

	public enum ReplayOutcome {

		REPLAYED, NOT_DEAD, NOT_FOUND

	}

	public JobPage list(JobStatus status, int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
		Page<SyncJob> result = (status != null) ? this.jobs.findByStatus(status, request)
				: this.jobs.findAll(request);
		return new JobPage(result.getContent().stream().map(JobSummary::from).toList(), result.getNumber(),
				result.getSize(), result.getTotalElements());
	}

	public Optional<JobDetail> detail(long id) {
		return this.jobs.findById(id)
			.map((job) -> JobDetail.from(job, this.jobs.findPayload(id).orElse(null)));
	}

	public ReplayOutcome replay(long id) {
		OffsetDateTime now = OffsetDateTime.ofInstant(this.clock.instant(), ZoneOffset.UTC)
			.truncatedTo(ChronoUnit.MICROS);
		if (this.jobs.replay(id, now)) {
			return ReplayOutcome.REPLAYED;
		}
		return this.jobs.existsById(id) ? ReplayOutcome.NOT_DEAD : ReplayOutcome.NOT_FOUND;
	}

	public JobStats stats() {
		return new JobStats(this.jobs.countByStatus(), this.jobs.endToEndLatency());
	}

}
