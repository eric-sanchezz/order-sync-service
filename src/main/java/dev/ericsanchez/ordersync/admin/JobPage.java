package dev.ericsanchez.ordersync.admin;

import java.util.List;

public record JobPage(List<JobSummary> items, int page, int size, long totalElements) {
}
