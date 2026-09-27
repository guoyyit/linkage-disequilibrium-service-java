package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.TaskProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.FilterResponse;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdFilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.VariantStat;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.filter.DefaultVariantFilter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantFilterTest {

    private FileSnapshotStore store;
    private DefaultVariantFilter filter;

    private static final Variant V1 = new Variant("chr1", 10, "A", "T");
    private static final Variant V2 = new Variant("chr1", 20, "A", "T");
    private static final Variant V3 = new Variant("chr2", 10, "A", "T");

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setDir(tmp.resolve("b").toString());
        store = new FileSnapshotStore(props, new ObjectMapper());
        store.recover();
        var importer = new DefaultImportService(new TsvBatchParser(), new DefaultImportValidator(), store);
        var ldService = new DefaultLdService(new ThreadTaskManager(), store,
                new DefaultLdCalculator(), new TaskProperties());
        filter = new DefaultVariantFilter(store, ldService);

        importer.importInMemory(new BatchImportRequest("b1",
                List.of("s1", "s2", "s3", "s4"),
                List.of(V1, V2, V3),
                List.of(
                        List.of(0, 0, 0, 1), // V1 af 0.125 maf 0.125
                        List.of(0, 0, 0, 1), // V2 perfectly correlated with V1
                        List.of(0, 0, 2, 2)  // V3 other chromosome, maf 0.5
                ),
                new ImportOptions(List.of(), 0, 0, 1.0, 1.0)));
    }

    @Test
    void chromosomeAndPositionWindowFilter() {
        FilterResponse r = filter.select(new LdFilterCriteria("chr1", 0, 15, 0, 1, 0, 0, null));
        assertEquals(List.of(V1), r.variants().stream().map(VariantStat::variant).toList());
        assertEquals(1, r.snapshotVersion());
    }

    @Test
    void mafAndMissingFilter() {
        // V3 dosages 0,0,2,2 -> af 0.5 maf 0.5; V1 af 0.375 -> maf 0.375
        FilterResponse r = filter.select(new LdFilterCriteria(null, 0, 0, 0.45, 1, 0, 0, null));
        assertEquals(List.of(V3), r.variants().stream().map(VariantStat::variant).toList());
    }

    @Test
    void ldPruningKeepsFirstOfCorrelatedPair() {
        FilterResponse r = filter.select(new LdFilterCriteria(null, 0, 0, 0, 1, 0.5, 0, null));
        List<Variant> kept = r.variants().stream().map(VariantStat::variant).toList();
        assertTrue(kept.contains(V1));
        assertTrue(kept.contains(V3));
        assertEquals(2, kept.size());
        // deterministic order
        assertEquals(kept.stream().sorted().toList(), kept);
    }

    @Test
    void sameCriteriaReproduceSameResult() {
        var c = new LdFilterCriteria(null, 0, 0, 0, 1, 0.5, 0, null);
        List<Variant> a = filter.select(c).variants().stream().map(VariantStat::variant).toList();
        List<Variant> b = filter.select(c).variants().stream().map(VariantStat::variant).toList();
        assertEquals(a, b);
    }

    @Test
    void noCriteriaReturnsAllSorted() {
        List<Variant> all = filter.select(LdFilterCriteria.none())
                .variants().stream().map(VariantStat::variant).toList();
        assertEquals(List.of(V1, V2, V3), all);
    }
}
