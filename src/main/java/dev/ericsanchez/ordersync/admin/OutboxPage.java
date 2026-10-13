package dev.ericsanchez.ordersync.admin;

import java.util.List;

public record OutboxPage(List<OutboxSummary> items, int page, int size, long totalElements) {
}
