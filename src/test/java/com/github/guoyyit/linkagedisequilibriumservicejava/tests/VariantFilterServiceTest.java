package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultVariantFilterService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.InMemoryDatasetStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantFilterServiceTest {

    private InMemoryDatasetStore store;
    private DefaultVariantFilterService filter;

    static Variant v(String id, String chrom, long pos) {
        return new Variant(id, chrom, pos, "A", "T");
    }

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new InMemoryDatasetStore(dir.toString());
        filter = new DefaultVariantFilterService(store, new DefaultLdCalculator());
        // A,B 完全连锁；C 与 A r²=0.5；D 在 2 号染色体且与 A r²=0.5
        List<Variant> variants = List.of(
                v("A", "1", 100), v("B", "1", 200), v("C", "1", 5000), v("D", "2", 100));
        List<String> samples = List.of("s0", "s1", "s2", "s3");
        int[][] g = {
                {0, 0, 0, 0},
                {1, 1, 0, 2},
                {1, 1, 2, 0},
                {2, 2, 2, 2}
        };
        Map<String, Map<String, Integer>> matrix = new java.util.HashMap<>();
        for (int i = 0; i < samples.size(); i++) {
            Map<String, Integer> row = new java.util.HashMap<>();
            for (int j = 0; j < variants.size(); j++) {
                row.put(variants.get(j).id(), g[i][j]);
            }
            matrix.put(samples.get(i), row);
        }
        DatasetSnapshot snap = new DatasetSnapshot(1L, List.of("b"), samples, variants, matrix);
        store.commit(toBatch(snap));
    }

    private static com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch
    toBatch(DatasetSnapshot snap) {
        List<List<Integer>> rows = new java.util.ArrayList<>();
        for (String s : snap.sampleIds()) {
            List<Integer> row = new java.util.ArrayList<>();
            for (Variant v : snap.variants()) {
                row.add(snap.matrix().get(s).get(v.id()));
            }
            rows.add(row);
        }
        return new com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch(
                "b", snap.sampleIds(), snap.variants(), rows);
    }

    private static FilterCriteria c(String chrom, Long lo, Long hi, Double maf,
                                    Double missing, Double r2, Long version) {
        return new FilterCriteria(chrom, lo, hi, maf, missing, r2, version);
    }

    @Test
    void filterByChromosome() {
        FilterResult r = filter.filter(c("chr2", null, null, null, null, null, null));
        assertEquals(List.of("D"), r.retained());
    }

    @Test
    void filterByWindow() {
        FilterResult r = filter.filter(c(null, 150L, 300L, null, null, null, null));
        assertEquals(List.of("B"), r.retained());
    }

    @Test
    void filterByMaf() {
        // D 剂量 [0,1,1,2] maf=0.5；C [0,0,2,2] maf=0.5；A/B maf=0.5 —— 全部 0.5
        // 用 >0.5 非法校验、0.5 全保留来验证；另用 0.6 触发非法
        assertEquals(4, filter.filter(c(null, null, null, 0.5, null, null, null))
                .retained().size());
        assertThrows(IllegalArgumentException.class,
                () -> filter.filter(c(null, null, null, 0.6, null, null, null)));
    }

    @Test
    void filterByMissingRate() {
        // 无缺失：阈值 0.0 仍全部保留
        assertEquals(4, filter.filter(c(null, null, null, null, 0.0, null, null))
                .retained().size());
    }

    @Test
    void ldPruningIsDeterministicAndKeepsIndependentVariants() {
        // r2 阈值 0.9：A、B 完全连锁(1.0) -> 去掉 B；C(0.5)、D 保留
        FilterCriteria criteria = c(null, null, null, null, null, 0.9, null);
        FilterResult r1 = filter.filter(criteria);
        FilterResult r2 = filter.filter(criteria);
        assertEquals(r1.retained(), r2.retained()); // 可复现、无漂移
        assertEquals(List.of("A", "C", "D"), r1.retained());
    }

    @Test
    void strictLdThresholdKeepsOnlyOneOfPerfectPair() {
        // r2 阈值 0.0：任何相关都剪枝。A 保留，B 因与 A r²=1 被剪
        FilterResult r = filter.filter(c(null, null, null, null, null, 0.0, null));
        assertEquals(1, r.retained().size());
        assertEquals("A", r.retained().get(0));
    }

    @Test
    void combinedCriteriaAndPinnedVersion() {
        // chr1 + 窗口 <=5000 + r2<=0.9 => A 与 B 去一, C 保留
        FilterResult r = filter.filter(c("1", null, 5000L, null, null, 0.9, 1L));
        assertEquals(1L, r.snapshotVersion());
        assertEquals(List.of("A", "C"), r.retained());
        // 指定不存在版本 -> 404 语义异常
        assertThrows(com.github.guoyyit.linkagedisequilibriumservicejava
                .service.SnapshotNotFoundException.class,
                () -> filter.filter(c(null, null, null, null, null, null, 42L)));
    }

    @Test
    void emptySnapshotReturnsEmpty() {
        InMemoryDatasetStore empty = new InMemoryDatasetStore(
                new java.io.File(System.getProperty("java.io.tmpdir"),
                        "ld-empty-" + System.nanoTime()).toString());
        DefaultVariantFilterService f = new DefaultVariantFilterService(empty,
                new DefaultLdCalculator());
        FilterResult r = f.filter(c(null, null, null, null, null, null, null));
        assertTrue(r.retained().isEmpty());
    }
}
