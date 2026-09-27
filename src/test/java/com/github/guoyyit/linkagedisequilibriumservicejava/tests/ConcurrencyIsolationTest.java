package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.importing.DefaultImportService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.TsvBatchParser;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.FileSnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.validator.DefaultImportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyIsolationTest {

    private static final int BATCHES = 12;
    private static final int SAMPLES_PER_BATCH = 4;

    private BatchImportRequest batchFor(int i) {
        List<String> samples = new ArrayList<>();
        for (int s = 0; s < SAMPLES_PER_BATCH; s++) {
            samples.add("s" + i + "_" + s);
        }
        Variant v = new Variant("chr1", 100 + i, "A", "T");
        List<Integer> row = new ArrayList<>();
        for (int s = 0; s < SAMPLES_PER_BATCH; s++) {
            row.add((i + s) % 3);
        }
        return new BatchImportRequest("batch-" + i, samples, List.of(v), List.of(row),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0));
    }

    @Test
    void concurrentImportsCommitAllBatchesExactlyOnce(@TempDir Path tmp) throws Exception {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        FileSnapshotStore store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        var importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), store);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        // Each batch imported twice concurrently: duplicates must not change outcome.
        for (int i = 0; i < BATCHES; i++) {
            int id = i;
            for (int rep = 0; rep < 2; rep++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    importer.importInMemory(batchFor(id));
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        DataSnapshot snapshot = store.current();
        assertEquals(BATCHES, snapshot.batchIds().size());
        assertEquals(BATCHES, snapshot.variants().size());
        assertEquals(BATCHES * SAMPLES_PER_BATCH, snapshot.samples().size());
        // matrix is rectangular
        for (byte[] row : snapshot.dosageMatrix()) {
            assertEquals(snapshot.samples().size(), row.length);
        }
    }

    @Test
    void readersOnlyObserveWholeSnapshots(@TempDir Path tmp) throws Exception {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        FileSnapshotStore store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        var importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), store);

        ExecutorService readers = Executors.newFixedThreadPool(4);
        ExecutorService writers = Executors.newFixedThreadPool(6);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();

        // Readers: for every observed version, internal invariants must hold.
        for (int r = 0; r < 4; r++) {
            readers.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        DataSnapshot s = store.current();
                        if (s.variants().size() != s.batchIds().size()) {
                            throw new AssertionError("partial snapshot observed");
                        }
                        for (byte[] row : s.dosageMatrix()) {
                            if (row.length != s.samples().size()) {
                                throw new AssertionError("partial matrix observed");
                            }
                        }
                    }
                } catch (Throwable t) {
                    readerFailure.compareAndSet(null, t);
                }
            });
        }
        CountDownLatch writerStart = new CountDownLatch(1);
        for (int i = 0; i < BATCHES; i++) {
            int id = i;
            writers.submit(() -> {
                try {
                    writerStart.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                importer.importInMemory(batchFor(id));
            });
        }
        writerStart.countDown();
        writers.shutdown();
        assertTrue(writers.awaitTermination(30, TimeUnit.SECONDS), "imports must finish");
        stop.countDown();
        readers.shutdown();
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS), "readers must finish");
        if (readerFailure.get() != null) {
            throw new AssertionError(readerFailure.get());
        }
    }

    @Test
    void incrementalResultMatchesFullRecompute(@TempDir Path tmp) throws Exception {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        FileSnapshotStore incremental = new FileSnapshotStore(props, new ObjectMapper());
        incremental.recover();
        var importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), incremental);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < BATCHES; i++) {
            int id = i;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                importer.importInMemory(batchFor(id));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // Sequential full recompute in commit-order-independent reference set:
        // all batches share disjoint samples and distinct variants, order must
        // not change variant set nor per-variant statistics.
        var incSnap = incremental.current();
        for (int i = 0; i < BATCHES; i++) {
            Variant v = new Variant("chr1", 100 + i, "A", "T");
            var stat = incSnap.statistics().get(v);
            assertEquals(4, stat.nonMissingSamples());
            double expectedAf = 0;
            for (int s = 0; s < SAMPLES_PER_BATCH; s++) {
                expectedAf += (i + s) % 3;
            }
            expectedAf /= 2.0 * SAMPLES_PER_BATCH;
            assertEquals(expectedAf, stat.alleleFrequency(), 1e-9);
        }
    }
}
