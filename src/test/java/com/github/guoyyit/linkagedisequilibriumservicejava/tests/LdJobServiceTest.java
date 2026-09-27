package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.JobStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ComputationCancelledException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdJobService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.InMemoryDatasetStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.JobNotFoundException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.LdCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdJobServiceTest {

    static Variant v(String id, long pos) {
        return new Variant(id, "1", pos, "A", "T");
    }

    private InMemoryDatasetStore storeWithOneBatch(Path dir) {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        store.commit(new GenotypeBatch("b", List.of("s0", "s1"),
                List.of(v("A", 1), v("B", 2), v("C", 3)),
                List.of(List.of(0, 0, 0), List.of(2, 2, 2))));
        return store;
    }

    /** 统计 computeAll 被调用次数的计算器。 */
    static final class CountingCalculator implements LdCalculator {
        final AtomicInteger invocations = new AtomicInteger();
        final LdCalculator delegate = new com.github.guoyyit.linkagedisequilibriumservicejava
                .service.DefaultLdCalculator();

        @Override
        public LdPair pair(DatasetSnapshot snapshot, String variant1, String variant2) {
            return delegate.pair(snapshot, variant1, variant2);
        }

        @Override
        public List<LdPair> computeAll(DatasetSnapshot snapshot, List<String> variantIds,
                                       ProgressCallback progress, CancellationToken cancel) {
            invocations.incrementAndGet();
            return delegate.computeAll(snapshot, variantIds, progress, cancel);
        }
    }

    @Test
    void duplicateAndConcurrentSubmissionRunsOnce(@TempDir Path dir) throws Exception {
        InMemoryDatasetStore store = storeWithOneBatch(dir);
        CountingCalculator calc = new CountingCalculator();
        DefaultLdJobService jobs = new DefaultLdJobService(store, calc);
        List<String> ids = List.of("A", "B", "C");

        LdJobState j1 = jobs.submit(1, ids, 60_000);
        LdJobState j2 = jobs.submit(1, List.of("C", "A", "B"), 60_000); // 同集合不同顺序
        assertSame(j1, j2);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    jobs.submit(1, ids, 60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            th.start();
        }
        start.countDown();
        done.await();

        waitForStatus(j1, JobStatus.COMPLETED);
        assertEquals(1, calc.invocations.get(), "同一计算只允许执行一次");
        assertEquals(3, jobs.results(j1.jobId()).orElseThrow().size());
    }

    @Test
    void progressReachesHundredOnCompletion(@TempDir Path dir) {
        InMemoryDatasetStore store = storeWithOneBatch(dir);
        DefaultLdJobService jobs = new DefaultLdJobService(store,
                new com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator());
        LdJobState job = jobs.submit(1, List.of("A", "B", "C"), 60_000);
        waitForStatus(job, JobStatus.COMPLETED);
        assertEquals(100, job.progressPercent());
    }

    @Test
    void cancelLeavesNoPartialResults(@TempDir Path dir) throws Exception {
        InMemoryDatasetStore store = storeWithOneBatch(dir);
        // 计算到第一对后阻塞，等待取消
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LdCalculator blocking = new LdCalculator() {
            @Override
            public LdPair pair(DatasetSnapshot snapshot, String v1, String v2) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<LdPair> computeAll(DatasetSnapshot snapshot, List<String> variantIds,
                                           ProgressCallback progress, CancellationToken cancel) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (cancel.isCancelled()) {
                    throw new ComputationCancelledException("cancelled");
                }
                return List.of(new LdPair("A", "B", 0, 1, 2));
            }
        };
        DefaultLdJobService jobs = new DefaultLdJobService(store, blocking);
        LdJobState job = jobs.submit(1, List.of("A", "B", "C"), 60_000);
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        JobStatus status = jobs.cancel(job.jobId());
        assertEquals(JobStatus.CANCELLED, status);
        release.countDown();
        waitForStatus(job, JobStatus.CANCELLED);
        assertTrue(jobs.results(job.jobId()).isEmpty(), "取消后不得返回部分结果");
        assertFalse(job.isTerminal() == false);
    }

    @Test
    void timeoutMarksJobAndNoResults(@TempDir Path dir) {
        InMemoryDatasetStore store = storeWithOneBatch(dir);
        // 极小超时；计算器每对都回调进度，服务据此判定超时
        LdCalculator slow = new com.github.guoyyit.linkagedisequilibriumservicejava
                .service.DefaultLdCalculator() {
            @Override
            public List<LdPair> computeAll(DatasetSnapshot snapshot, List<String> variantIds,
                                           ProgressCallback progress, CancellationToken cancel) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ComputationCancelledException("interrupted");
                }
                progress.onProgress(50);
                return super.computeAll(snapshot, variantIds, progress, cancel);
            }
        };
        DefaultLdJobService jobs = new DefaultLdJobService(store, slow);
        LdJobState job = jobs.submit(1, List.of("A", "B", "C", "A", "B", "C"), 10);
        waitForStatus(job, JobStatus.TIMEOUT);
        assertTrue(jobs.results(job.jobId()).isEmpty());
    }

    @Test
    void unknownJobAndSnapshotRaise(@TempDir Path dir) {
        InMemoryDatasetStore store = storeWithOneBatch(dir);
        DefaultLdJobService jobs = new DefaultLdJobService(store,
                new com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator());
        assertThrows(JobNotFoundException.class, () -> jobs.cancel("nope"));
        assertThrows(com.github.guoyyit.linkagedisequilibriumservicejava
                .service.SnapshotNotFoundException.class,
                () -> jobs.submit(99, List.of("A"), 60_000));
    }

    private static void waitForStatus(LdJobState job, JobStatus expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && job.status() != expected) {
            Thread.onSpinWait();
        }
        assertEquals(expected, job.status());
    }
}
