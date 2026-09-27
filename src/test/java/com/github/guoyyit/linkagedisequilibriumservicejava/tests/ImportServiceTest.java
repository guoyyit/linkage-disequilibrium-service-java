package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchState;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.importing.DefaultImportService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.TsvBatchParser;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.FileSnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.validator.DefaultImportValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    private FileSnapshotStore store;
    private DefaultImportService importer;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("batches").toString());
        store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), store);
    }

    private static BatchImportRequest batch(String id, List<String> samples,
                                            List<Variant> variants, List<List<Integer>> g) {
        return new BatchImportRequest(id, samples, variants, g,
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0));
    }

    @Test
    void duplicateImportIsIdempotent() {
        var request = batch("b1", List.of("s1", "s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(0, 1)));
        BatchResult first = importer.importInMemory(request);
        BatchResult second = importer.importInMemory(request);

        assertFalse(first.duplicated());
        assertTrue(second.duplicated());
        assertEquals(1, store.batches().size());
        assertEquals(1, store.current().version());
    }

    @Test
    void sameBatchIdDifferentContentRejected() {
        importer.importInMemory(batch("b1", List.of("s1"),
                List.of(new Variant("chr1", 10, "A", "T")), List.of(List.of(0))));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> importer.importInMemory(batch("b1", List.of("s1"),
                        List.of(new Variant("chr1", 10, "A", "T")), List.of(List.of(2)))));
        assertTrue(ex.getMessage().contains("different content"));
        assertEquals(1, store.current().version());
    }

    @Test
    void samplesAndVariantsIncrementAcrossBatches() {
        importer.importInMemory(batch("b1", List.of("s1", "s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(0, 2))));
        importer.importInMemory(batch("b2", List.of("s2", "s3"),
                List.of(new Variant("chr1", 20, "G", "C")),
                List.of(List.of(1, 0))));

        var snapshot = store.current();
        assertEquals(2, snapshot.version());
        assertEquals(List.of("s1", "s2", "s3"), snapshot.samples());
        assertEquals(2, snapshot.variants().size());

        byte[][] m = snapshot.dosageMatrix();
        int v1 = snapshot.indexOfVariant(new Variant("chr1", 10, "A", "T"));
        int s1 = snapshot.indexOfSample("s1");
        int s3 = snapshot.indexOfSample("s3");
        assertEquals(0, m[v1][s1]);
        // s3 was never observed at v1 -> missing
        assertEquals(-1, m[v1][s3]);
    }

    @Test
    void conflictingNonMissingValueAcrossBatchesRejected() {
        importer.importInMemory(batch("b1", List.of("s1"),
                List.of(new Variant("chr1", 10, "A", "T")), List.of(List.of(0))));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> importer.importInMemory(batch("b2", List.of("s1"),
                        List.of(new Variant("chr1", 10, "A", "T")), List.of(List.of(2)))));
        assertTrue(ex.errors().stream().anyMatch(e -> "GENOTYPE_CONFLICT".equals(e.code())));
        // rejected batch must not be visible
        assertFalse(store.current().containsBatch("b2"));
    }

    @Test
    void missingDoesNotConflictAndFillsUnseen() {
        importer.importInMemory(batch("b1", List.of("s1", "s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(1, -1))));
        importer.importInMemory(batch("b2", List.of("s2"),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of(List.of(2))));
        byte[][] m = store.current().dosageMatrix();
        int v = store.current().indexOfVariant(new Variant("chr1", 10, "A", "T"));
        assertEquals(1, m[v][0]);
        assertEquals(2, m[v][1]);
        assertEquals(BatchState.COMMITTED, store.batches().get(1).state());
    }

    @Test
    void chromosomeFilterRejectsBatch() {
        var request = new BatchImportRequest("b1", List.of("s1"),
                List.of(new Variant("chrX", 10, "A", "T")),
                List.of(List.of(0)),
                new ImportOptions(List.of("chr1"), 0, 0, 1.0, 1.0));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> importer.importInMemory(request));
        assertTrue(ex.errors().stream()
                .anyMatch(e -> "VARIANT_CHROMOSOME_REJECTED".equals(e.code())));
        assertEquals(0, store.batches().size());
    }
}
