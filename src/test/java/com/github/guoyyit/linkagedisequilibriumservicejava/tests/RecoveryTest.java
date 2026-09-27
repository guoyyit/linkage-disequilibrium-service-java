package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.importing.DefaultImportService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.TsvBatchParser;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.FileSnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.validator.DefaultImportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    private FileSnapshotStore newStore(Path dir) {
        StorageProperties props = new StorageProperties();
        props.setDir(dir.toString());
        FileSnapshotStore store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        return store;
    }

    private void commitTwoBatches(FileSnapshotStore store) {
        var importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), store);
        importer.importInMemory(new BatchImportRequest("b1", List.of("s1", "s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(0, 2)),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0)));
        importer.importInMemory(new BatchImportRequest("b2", List.of("s2", "s3"),
                List.of(new Variant("chr1", 20, "G", "C")),
                List.of(List.of(1, 0)),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0)));
    }

    @Test
    void committedBatchesReplayIntoSameSnapshot(@TempDir Path dir) {
        FileSnapshotStore before = newStore(dir);
        commitTwoBatches(before);

        FileSnapshotStore restarted = newStore(dir);
        var a = before.current();
        var b = restarted.current();

        assertEquals(a.version(), b.version());
        assertEquals(a.batchIds(), b.batchIds());
        assertEquals(a.samples(), b.samples());
        assertEquals(a.variants(), b.variants());
        for (int r = 0; r < a.variants().size(); r++) {
            for (int c = 0; c < a.samples().size(); c++) {
                assertEquals(a.dosageMatrix()[r][c], b.dosageMatrix()[r][c]);
            }
        }
        assertEquals(2, restarted.batches().size());
    }

    @Test
    void importAfterRecoveryContinuesVersioning(@TempDir Path dir) {
        FileSnapshotStore before = newStore(dir);
        commitTwoBatches(before);

        FileSnapshotStore restarted = newStore(dir);
        var importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), restarted);
        importer.importInMemory(new BatchImportRequest("b3", List.of("s1"),
                List.of(new Variant("chr1", 30, "C", "G")),
                List.of(List.of(1)),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0)));

        assertEquals(3, restarted.current().version());
        assertTrue(restarted.current().containsBatch("b1"));
        assertTrue(restarted.current().containsBatch("b2"));
        assertTrue(restarted.current().containsBatch("b3"));
    }

    @Test
    void staleTmpFilesAreIgnoredOnRecovery(@TempDir Path dir) throws Exception {
        FileSnapshotStore before = newStore(dir);
        commitTwoBatches(before);
        // simulate an interrupted write
        Files.writeString(dir.resolve("partial.json.tmp"), "{broken json");

        FileSnapshotStore restarted = newStore(dir);
        assertEquals(2, restarted.batches().size());
        assertTrue(Files.notExists(dir.resolve("partial.json.tmp")),
                "stale tmp file should be cleaned on recovery");
    }
}
