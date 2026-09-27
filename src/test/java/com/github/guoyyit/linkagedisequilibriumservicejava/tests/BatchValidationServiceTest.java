package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.BatchFilterOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.BatchRejectedException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultBatchValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchValidationServiceTest {

    private final DefaultBatchValidationService service = newService(0.0, 0.25);

    static DefaultBatchValidationService newService(double minMaf, double maxMissing) {
        DefaultBatchValidationService s = new DefaultBatchValidationService();
        ReflectionTestUtils.setField(s, "defaultMinMaf", minMaf);
        ReflectionTestUtils.setField(s, "defaultMaxMissingRate", maxMissing);
        return s;
    }

    static Variant v(String id, long pos) {
        return new Variant(id, "1", pos, "A", "T");
    }

    static GenotypeBatch batch(String bid, List<String> samples, List<Variant> variants,
                               List<List<Integer>> g) {
        return new GenotypeBatch(bid, samples, variants, g);
    }

    static BatchFilterOptions opts(Double minMaf, Double maxMissing, Long lo, Long hi) {
        return new BatchFilterOptions(minMaf, maxMissing, lo, hi);
    }

    private GenotypeBatch validBatch(String bid) {
        return batch(bid,
                List.of("s0", "s1", "s2", "s3"),
                List.of(v("A", 1), v("B", 2)),
                List.of(
                        List.of(0, 0),
                        List.of(1, 1),
                        List.of(1, 2),
                        List.of(2, 2)));
    }

    @Test
    void acceptsValidBatchAndNormalizesChrom() {
        GenotypeBatch out = service.validate(
                batch("b", List.of("s0", "s1"),
                        List.of(new Variant("A", "chr1", 10, "a", "t"), v("B", 20)),
                        List.of(List.of(0, 0), List.of(2, 2))),
                opts(null, 1.0, null, null));
        assertEquals("1", out.variants().get(0).chrom());
    }

    @Test
    void rejectsIllegalGenotypeCodeWithLocation() {
        GenotypeBatch bad = batch("b", List.of("s0", "s1"), List.of(v("A", 1)),
                List.of(List.of(0), List.of(5)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, opts(null, 1.0, null, null)));
        assertTrue(ex.errors().stream().anyMatch(e ->
                e.contains("非法基因型编码") && e.contains("位点[A 1:1]") && e.contains("样本行#1")));
    }

    @Test
    void rejectsMatrixDimensionMismatch() {
        GenotypeBatch bad = batch("b", List.of("s0", "s1"), List.of(v("A", 1), v("B", 2)),
                List.of(List.of(0, 1), List.of(2)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, opts(null, 1.0, null, null)));
        assertTrue(ex.errors().get(0).contains("样本行长度"));
    }

    @Test
    void rejectsVariantMissingRateAboveThreshold() {
        // A 位点 2/4 缺失 = 0.5 > 0.25
        GenotypeBatch bad = batch("b", List.of("s0", "s1", "s2", "s3"),
                List.of(v("A", 1)),
                List.of(List.of(0), List.of(-1), List.of(2), List.of(-1)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, null));
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("位点缺失率")));
    }

    @Test
    void rejectsSampleMissingRateAboveThreshold() {
        GenotypeBatch bad = batch("b", List.of("s0", "s1"),
                List.of(v("A", 1), v("B", 2), v("C", 3), v("D", 4)),
                List.of(
                        List.of(0, 0, 1, 2),
                        List.of(-1, -1, -1, 2))); // 3/4 缺失
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, opts(null, 0.5, null, null)));
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("样本缺失率")));
    }

    @Test
    void rejectsMonomorphicAndLowMaf() {
        // 全 0 -> 单态
        GenotypeBatch mono = batch("b", List.of("s0", "s1", "s2"), List.of(v("A", 1)),
                List.of(List.of(0), List.of(0), List.of(0)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(mono, opts(null, 1.0, null, null)));
        assertTrue(ex.errors().get(0).contains("单态"));

        // 8 个 2 中只有 1 个 1：MAF≈0.0625 < 0.1
        List<List<Integer>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(List.of(2));
        }
        rows.add(List.of(1));
        GenotypeBatch lowMaf = batch("b2",
                List.of("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7"),
                List.of(v("A", 1)), rows);
        BatchRejectedException ex2 = assertThrows(BatchRejectedException.class,
                () -> service.validate(lowMaf, opts(0.1, 1.0, null, null)));
        assertTrue(ex2.errors().get(0).contains("次等位基因频率"));
    }

    @Test
    void rejectsPositionOutOfWindow() {
        GenotypeBatch bad = batch("b", List.of("s0", "s1"), List.of(v("A", 100)),
                List.of(List.of(0), List.of(2)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, opts(null, 1.0, 200L, 300L)));
        assertTrue(ex.errors().get(0).contains("窗口"));
    }

    @Test
    void rejectsDuplicateSamplesAndVariants() {
        GenotypeBatch bad = batch("b", List.of("s0", "s0"),
                List.of(v("A", 1), v("A", 2)),
                List.of(List.of(0, 0), List.of(2, 2)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validate(bad, opts(null, 1.0, null, null)));
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("样本在批次内重复")));
        assertTrue(ex.errors().stream().anyMatch(e -> e.contains("位点在批次内重复")));
    }

    @Test
    void missingIsAcceptedWithinThreshold() {
        // 4 样本 x 4 位点，仅 (s3,A) 一个缺失：样本/位点缺失率均为 1/4，恰好等于阈值，接受
        GenotypeBatch g = batch("b",
                List.of("s0", "s1", "s2", "s3"),
                List.of(v("A", 1), v("B", 2), v("C", 3), v("D", 4)),
                List.of(
                        List.of(0, 0, 0, 2),
                        List.of(1, 0, 1, 1),
                        List.of(2, 1, 1, 2),
                        List.of(-1, 2, 2, 2)));
        assertDoesNotThrow(() -> service.validate(g, opts(null, 0.25, null, null)));
    }

    @Test
    void rejectsConflictingExistingGenotypeButAllowsMissingFill() {
        DatasetSnapshot existing = new DatasetSnapshot(1L, List.of("b0"),
                List.of("s0", "s1"), List.of(v("A", 1)),
                Map.of("s0", Map.of("A", 0), "s1", Map.of("A", 2)));

        // 矛盾值
        GenotypeBatch conflict = batch("b1", List.of("s0"), List.of(v("A", 1)),
                List.of(List.of(2)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validateAgainst(conflict, existing));
        assertTrue(ex.errors().get(0).contains("基因型与已提交记录冲突"));

        // 已有缺失，新值补全 -> 允许
        DatasetSnapshot withMissing = new DatasetSnapshot(1L, List.of("b0"),
                List.of("s0"), List.of(v("A", 1)),
                Map.of("s0", new java.util.HashMap<>(Map.of("A", -1))));
        GenotypeBatch fill = batch("b1", List.of("s0"), List.of(v("A", 1)),
                List.of(List.of(1)));
        assertDoesNotThrow(() -> service.validateAgainst(fill, withMissing));
    }

    @Test
    void rejectsConflictingVariantDefinition() {
        DatasetSnapshot existing = new DatasetSnapshot(1L, List.of("b0"),
                List.of("s0"), List.of(v("A", 1)), Map.of("s0", Map.of("A", 0)));
        GenotypeBatch redef = batch("b1", List.of("s0"),
                List.of(new Variant("A", "1", 1, "G", "C")), List.of(List.of(0)));
        BatchRejectedException ex = assertThrows(BatchRejectedException.class,
                () -> service.validateAgainst(redef, existing));
        assertTrue(ex.errors().get(0).contains("位点定义"));
    }

    @Test
    void validBatchPasses() {
        assertDoesNotThrow(() -> service.validate(validBatch("b"), null));
    }
}
