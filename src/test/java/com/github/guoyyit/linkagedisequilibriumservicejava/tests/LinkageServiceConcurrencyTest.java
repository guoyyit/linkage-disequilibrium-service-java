package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.BatchFilterOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportBatchRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.VariantDto;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.BatchRejectedException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultBatchValidationService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdJobService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLinkageService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultVariantFilterService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.InMemoryDatasetStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面端到端（真实组件拼装，不经 HTTP）：幂等导入、拒绝定位、并发导入/查询的快照一致性。
 */
class LinkageServiceConcurrencyTest {

    private DefaultLinkageService build(Path dir) {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        DefaultBatchValidationService validation = new DefaultBatchValidationService();
        ReflectionTestUtils.setField(validation, "defaultMinMaf", 0.0);
        ReflectionTestUtils.setField(validation, "defaultMaxMissingRate", 0.25);
        DefaultLdCalculator calc = new DefaultLdCalculator();
        DefaultVariantFilterService filter = new DefaultVariantFilterService(store, calc);
        DefaultLdJobService jobs = new DefaultLdJobService(store, calc);
        DefaultLinkageService facade = new DefaultLinkageService(
                store, validation, filter, jobs);
        ReflectionTestUtils.setField(facade, "defaultJobTimeoutMillis", 600_000L);
        return facade;
    }

    private static ImportBatchRequest polymorphicBatch(String bid, String... variantIds) {
        List<VariantDto> variants = new ArrayList<>();
        long pos = 100;
        for (String id : variantIds) {
            variants.add(new VariantDto(id, "1", pos++, "A", "T"));
        }
        List<String> samples = List.of("s0", "s1", "s2", "s3");
        List<List<Integer>> g = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Integer> row = new ArrayList<>();
            for (int j = 0; j < variantIds.length; j++) {
                // 所有位点列相同（完全连锁），列为 0/1/1/2，多态且无缺失
                row.add(List.of(0, 1, 1, 2).get(i));
            }
            g.add(row);
        }
        return new ImportBatchRequest(bid, samples, variants, g,
                new BatchFilterOptions(null, 1.0, null, null));
    }

    @Test
    void duplicateImportDoesNotChangeResult(@TempDir Path dir) {
        DefaultLinkageService svc = build(dir);
        ImportResult first = svc.importBatch(polymorphicBatch("b1", "A", "B"));
        assertFalse(first.alreadyPresent());
        assertEquals(1L, first.snapshotVersion());

        ImportResult second = svc.importBatch(polymorphicBatch("b1", "A", "B"));
        assertTrue(second.alreadyPresent());
        assertEquals(1L, second.snapshotVersion());
        assertEquals(2, second.variantCount());
        // 同一批次内容不同也不会改变任何数据
        ImportResult third = svc.importBatch(polymorphicBatch("b1", "X"));
        assertTrue(third.alreadyPresent());
        assertEquals(2, third.variantCount());
    }

    @Test
    void invalidBatchRejectedWithLocatableErrorsAndNoCommit(@TempDir Path dir) {
        DefaultLinkageService svc = build(dir);
        ImportBatchRequest bad = new ImportBatchRequest("bad",
                List.of("s0", "s1"),
                List.of(new VariantDto("A", "1", 10L, "A", "T")),
                List.of(List.of(0), List.of(9)), // 非法编码
                null);
        BatchRejectedException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BatchRejectedException.class, () -> svc.importBatch(bad));
        assertEquals("bad", ex.batchId());
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("非法基因型编码")));
        // 拒绝后没有任何已提交数据
        assertTrue(svc.currentSnapshot().isEmpty());
    }

    @Test
    void incrementalSamplesAndVariantsAcrossBatches(@TempDir Path dir) {
        DefaultLinkageService svc = build(dir);
        svc.importBatch(polymorphicBatch("b1", "A"));
        ImportResult r2 = svc.importBatch(polymorphicBatch("b2", "A", "B"));
        assertEquals(2L, r2.snapshotVersion());
        // B 为新位点；样本仍为同一组 4 个
        assertEquals(2, r2.variantCount());
        assertEquals(4, r2.sampleCount());
    }

    @Test
    void concurrentImportsAndQueriesSeeOnlyCommittedSnapshots(@TempDir Path dir)
            throws Exception {
        DefaultLinkageService svc = build(dir);
        int batches = 12;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger idx = new AtomicInteger();
        List<Thread> importers = new ArrayList<>();
        Set<Throwable> errors = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    int i;
                    while ((i = idx.getAndIncrement()) < batches) {
                        svc.importBatch(polymorphicBatch("b" + i, "V" + i));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            importers.add(th);
            th.start();
        }

        // 并发查询：每一次看到的（版本,位点数）都必须自洽：位点数 = 已提交批次数（每批一个新位点）
        List<Thread> readers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + 10_000;
                    while (System.currentTimeMillis() < deadline) {
                        var snap = svc.currentSnapshot();
                        if (snap.isPresent()) {
                            var s = snap.get();
                            assertEquals(s.batchIds().size(), s.variantCount(),
                                    "读到了部分写入的快照: " + s);
                            // 筛选必须作用于某个已提交版本并返回自洽数量
                            var fr = svc.filter(new FilterCriteria(
                                    null, null, null, null, null, null, s.version()));
                            assertEquals(s.version(), fr.snapshotVersion());
                            assertEquals(s.variantCount(), fr.retained().size());
                        }
                        if (svc.currentSnapshot().map(s -> s.version()).orElse(0L) == batches) {
                            break;
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            readers.add(th);
            th.start();
        }

        start.countDown();
        for (Thread th : importers) {
            th.join();
        }
        for (Thread th : readers) {
            th.join(15_000);
            assertFalse(th.isAlive());
        }
        assertTrue(errors.isEmpty(), errors.toString());

        var finalSnap = svc.currentSnapshot().orElseThrow();
        assertEquals(batches, finalSnap.version());
        assertEquals(batches, finalSnap.variantCount());
        // 再重复并发导入相同批次，版本与结果不再变化
        CountDownLatch again = new CountDownLatch(1);
        List<Thread> dup = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                try {
                    again.await();
                    for (int i = 0; i < batches; i++) {
                        ImportResult r = svc.importBatch(polymorphicBatch("b" + i, "V" + i));
                        assertTrue(r.alreadyPresent());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            dup.add(th);
            th.start();
        }
        again.countDown();
        for (Thread th : dup) {
            th.join();
        }
        assertEquals(batches, svc.currentSnapshot().orElseThrow().version());
        assertEquals(new HashSet<>(finalSnap.batchIds()),
                new HashSet<>(svc.currentSnapshot().orElseThrow().batchIds()));
    }
}
