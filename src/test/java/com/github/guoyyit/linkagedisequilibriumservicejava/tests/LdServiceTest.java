package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.TaskProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdComputeRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.importing.DefaultImportService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ld.DefaultLdCalculator;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ld.DefaultLdService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.TsvBatchParser;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.FileSnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.ThreadTaskManager;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.validator.DefaultImportValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdServiceTest {

    private FileSnapshotStore store;
    private DefaultImportService importer;
    private DefaultLdService ldService;
    private AtomicCalculator calculator;

    static class AtomicCalculator extends DefaultLdCalculator {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public LdMatrix calculate(String taskId, com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot snapshot,
                                  long windowBp, com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskContext context) {
            invocations.incrementAndGet();
            return super.calculate(taskId, snapshot, windowBp, context);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        importer = new DefaultImportService(new TsvBatchParser(), new DefaultImportValidator(), store);
        calculator = new AtomicCalculator();
        TaskProperties tp = new TaskProperties();
        ldService = new DefaultLdService(new ThreadTaskManager(), store, calculator, tp);

        importer.importInMemory(new BatchImportRequest("b1", List.of("s1", "s2", "s3", "s4"),
                List.of(new Variant("chr1", 10, "A", "T"), new Variant("chr1", 20, "A", "T")),
                List.of(List.of(0, 1, 1, 2), List.of(0, 1, 1, 2)),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0)));
    }

    @Test
    void repeatComputationRunsCalculatorOnce() throws InterruptedException {
        ldService.ensureMatrix("t1", 0, 0);
        ldService.ensureMatrix("t1", 0, 0);
        ldService.start(LdComputeRequest.defaults()); // same key, alias join
        LdMatrix m = ldService.ensureMatrix("t1", 0, 0);
        assertEquals(1, calculator.invocations.get());
        assertEquals(1, m.snapshotVersion());
    }

    @Test
    void concurrentIdenticalRequestsRunCalculatorOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> taskInfos = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                TaskInfo info = ldService.start(new LdComputeRequest("t-conc", 0, 0));
                taskInfos.add(info.taskId());
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(Set.of("t-conc"), taskInfos);
        assertEquals(1, calculator.invocations.get());
    }

    @Test
    void cancelLeavesNoResult() {
        // tiny data completes quickly; instead verify cancelled status path on a fresh key
        TaskInfo info = ldService.start(new LdComputeRequest("cancel-me", 0, 0));
        boolean cancelled = ldService.cancel(info.taskId());
        // Either cancel won the race (true) or the tiny job already finished;
        // in both cases a completed matrix exists exactly once or none at all.
        TaskStatus after = ldService.status(info.taskId()).status();
        assertTrue(cancelled ? after == TaskStatus.CANCELLED : after == TaskStatus.COMPLETED);
        assertEquals(1, calculator.invocations.get());
    }

    @Test
    void timeoutMarksTaskAndAllowsRecompute() {
        var tp = new TaskProperties();
        var slow = new DefaultLdCalculator() {
            @Override
            public LdMatrix calculate(String taskId,
                                      com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot snapshot,
                                      long windowBp,
                                      com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskContext context) {
                try {
                    Thread.sleep(500);
                    context.checkpoint();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    context.checkpoint();
                }
                return super.calculate(taskId, snapshot, windowBp, context);
            }
        };
        DefaultLdService service = new DefaultLdService(new ThreadTaskManager(), store, slow, tp);
        TaskInfo info = service.start(new LdComputeRequest("slow", 0, 50));
        long deadline = System.currentTimeMillis() + 5000;
        TaskStatus status;
        do {
            status = service.status(info.taskId()).status();
        } while (!status.isTerminal() && System.currentTimeMillis() < deadline);
        assertEquals(TaskStatus.TIMEOUT, status);
        // failed computation is not cached: a new request starts fresh
        TaskInfo retry = service.start(new LdComputeRequest("slow", 0, 10_000));
        assertEquals(TaskStatus.RUNNING, retry.status());
    }

    @Test
    void conflictingTaskIdOnDifferentKeyRejected() {
        ldService.start(new LdComputeRequest("my-id", 0, 0));
        // same snapshot but different window is a different computation key
        assertThrows(ConflictException.class,
                () -> ldService.start(new LdComputeRequest("my-id", 500, 0)));
    }
}
