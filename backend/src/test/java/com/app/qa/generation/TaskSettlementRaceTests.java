package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 3C-SET-04：纯竞态 seam 跑足 1000 轮，数据库一致性另由集成测试覆盖。 */
class TaskSettlementRaceTests {

    @Test
    void 一千轮完成停止超时三方竞态最多一个终态和一次资源释放() throws Exception {
        var executor = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 1_000; round++) {
                var repository = new CasRepository();
                var releases = new AtomicInteger();
                var terminals = new CopyOnWriteArrayList<TaskSettlement.Outcome>();
                var settlement = new TaskSettlement(
                        new GenerationTaskId(UUID.randomUUID()), repository,
                        releases::incrementAndGet, terminals::add);
                var requests = List.of(
                        TaskSettlement.Request.completed("完整回答"),
                        TaskSettlement.Request.stopped("部分回答"),
                        TaskSettlement.Request.timedOut());
                var barrier = new CyclicBarrier(3);
                var outcomes = new CopyOnWriteArrayList<TaskSettlement.Outcome>();
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (var request : requests) {
                    futures.add(executor.submit(() -> {
                        barrier.await();
                        outcomes.add(settlement.trySettle(request));
                        return null;
                    }));
                }
                for (var future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }

                assertThat(outcomes).as("round %s", round)
                        .filteredOn(TaskSettlement.Outcome::won).hasSize(1);
                assertThat(outcomes).as("round %s", round)
                        .extracting(TaskSettlement.Outcome::status)
                        .containsOnly(repository.winner.get().status());
                assertThat(terminals).as("round %s", round).hasSize(1);
                assertThat(releases).as("round %s", round).hasValue(1);
                assertThat(repository.persistedMessages).as("round %s", round).hasValueLessThanOrEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class CasRepository implements TaskSettlementRepository {
        private final AtomicReference<PersistenceResult> winner = new AtomicReference<>();
        private final AtomicInteger persistedMessages = new AtomicInteger();

        @Override
        public PersistenceResult trySettle(GenerationTaskId taskId, TaskSettlement.Request request) {
            var won = new PersistenceResult(
                    true, request.status(), request.terminalReason(),
                    request.hasAnswer() ? UUID.randomUUID() : null, request.hasAnswer());
            if (winner.compareAndSet(null, won)) {
                if (request.hasAnswer()) persistedMessages.incrementAndGet();
                return won;
            }
            var settled = winner.get();
            return new PersistenceResult(
                    false, settled.status(), settled.terminalReason(),
                    settled.assistantMessageId(), settled.saved());
        }
    }
}
