package dev.ericsanchez.ordersync.admin;

import java.util.Map;

import dev.ericsanchez.ordersync.job.JobStatus;
import dev.ericsanchez.ordersync.job.SyncJobQueries.EndToEndLatency;

/** Job counts per status (all statuses present, zero included) and end-to-end latency of DONE jobs in ms. */
public record JobStats(Map<JobStatus, Long> byStatus, EndToEndLatency endToEndMs) {
}
