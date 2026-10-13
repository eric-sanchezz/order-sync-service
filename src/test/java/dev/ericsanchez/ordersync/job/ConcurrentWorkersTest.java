package dev.ericsanchez.ordersync.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import dev.ericsanchez.ordersync.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConcurrentWorkersTest extends IntegrationTest {

	private static final String ORDER = """
			{ "id": 1001, "number": "1001", "currency": "EUR", "total": "12.50",
			  "customer": { "id": 42 },
			  "line_items": [ { "sku": "TORN-M6-100", "name": "Tornillo", "quantity": 1, "price": "12.50" } ] }
			""";

	@Autowired
	private PlatformTransactionManager transactionManager;

	// Breaks if: the claim loses FOR UPDATE SKIP LOCKED. Both workers then pick the five row-locked jobs, wait for the
	// lock, and both claim and send them once it is released. Without that lock the race would be left to chance.
	@Test
	void twoWorkersPollingAtOnceNeverSendTheSameOrderTwice() throws Exception {
		this.erp.register(post(urlEqualTo(SALES_ORDERS))
			.willReturn(aResponse().withStatus(201).withBody("{\"orderId\":\"ERP-8\"}")));
		List<String> eventIds = IntStream.rangeClosed(1, 20).mapToObj((i) -> "evt-t8-" + i).toList();
		eventIds.forEach((eventId) -> postSignedOrder(eventId, ORDER));

		List<Future<?>> polls = new CopyOnWriteArrayList<>();
		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			new TransactionTemplate(this.transactionManager).executeWithoutResult((otherTransaction) -> {
				this.jdbc.sql("SELECT id FROM sync_job WHERE id <= 5 FOR UPDATE").query(Long.class).list();
				CountDownLatch start = new CountDownLatch(1);
				polls.add(workers.submit(() -> awaitAndPoll(start)));
				polls.add(workers.submit(() -> awaitAndPoll(start)));
				start.countDown();
				await().atMost(Duration.ofSeconds(5))
					.until(() -> polls.stream().allMatch(Future::isDone) || workersWaitingForALock() == 2);
			});
			for (Future<?> poll : polls) {
				poll.get();
			}
		}
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			this.worker.pollOnce();
			assertThat(this.jdbc.sql("SELECT count(*) FROM sync_job WHERE status = 'DONE'").query(Long.class).single())
				.isEqualTo(20);
		});

		List<LoggedRequest> requests = this.erp.find(postRequestedFor(urlEqualTo(SALES_ORDERS)));
		assertThat(requests.stream()
			.collect(Collectors.groupingBy((request) -> request.getHeader("Idempotency-Key"), Collectors.counting())))
			.containsOnlyKeys(eventIds)
			.allSatisfy((eventId, calls) -> assertThat(calls).as(eventId).isOne());
	}

	private Void awaitAndPoll(CountDownLatch start) throws InterruptedException {
		start.await();
		this.worker.pollOnce();
		return null;
	}

	private long workersWaitingForALock() {
		return this.jdbc.sql("""
				SELECT count(*) FROM pg_stat_activity
				WHERE wait_event_type = 'Lock' AND datname = current_database()
				""").query(Long.class).single();
	}

}
