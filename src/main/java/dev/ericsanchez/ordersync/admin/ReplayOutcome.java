package dev.ericsanchez.ordersync.admin;

/** Result of a replay request: only DEAD jobs and messages can be replayed. */
public enum ReplayOutcome {

	REPLAYED, NOT_DEAD, NOT_FOUND

}
