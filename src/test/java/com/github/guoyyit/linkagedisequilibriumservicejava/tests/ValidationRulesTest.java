package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationRulesTest {

    private DefaultImportService importer;
    private FileSnapshotStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        importer = new DefaultImportService(new TsvBatchParser(),
                new DefaultImportValidator(), store);
    }

    private BatchImportRequest batch(ImportOptions options, List<List<Integer>> g,
                                     List<Variant> variants, List<String> samples) {
        return new BatchImportRequest("b1", samples, variants, g, options);
    }

    @Test
    void rejectsSampleMissingOverThreshold() {
        // 2 samples, 2 variants; s2 missing at both variants -> 100% missing
        var req = batch(new ImportOptions(List.of(), 0, 0, 0.5, 1.0),
                List.of(List.of(0, -1), List.of(2, -1)),
                List.of(new Variant("chr1", 10, "A", "T"), new Variant("chr1", 20, "A", "T")),
                List.of("s1", "s2"));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> importer.importInMemory(req));
        assertTrue(ex.errors().stream().anyMatch(e -> "SAMPLE_MISSING_TOO_HIGH".equals(e.code())
                && "s2".equals(e.sample())));
    }

    @Test
    void rejectsVariantMissingOverThreshold() {
        var req = batch(new ImportOptions(List.of(), 0, 0, 1.0, 0.4),
                List.of(List.of(-1, -1, 0)),
                List.of(new Variant("chr1", 10, "A", "T")),
                List.of("s1", "s2", "s3"));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> importer.importInMemory(req));
        assertTrue(ex.errors().stream().anyMatch(e ->
                "VARIANT_MISSING_TOO_HIGH".equals(e.code()) && e.row() == 1));
    }

    @Test
    void rejectsVariantOutsidePositionWindow() {
        var req = batch(new ImportOptions(List.of("chr1"), 100, 200, 1.0, 1.0),
                List.of(List.of(0)),
                List.of(new Variant("chr1", 50, "A", "T")),
                List.of("s1"));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> importer.importInMemory(req));
        assertTrue(ex.errors().stream().anyMatch(e -> "VARIANT_POSITION_REJECTED".equals(e.code())));
    }
}
