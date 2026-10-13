package dev.ericsanchez.ordersync.job;

/** Lifecycle of a sync job. DONE and DEAD are final; only a manual replay moves a DEAD job back to PENDING. */
public enum JobStatus {

	PENDING, IN_PROGRESS, RETRY, DONE, DEAD

}
