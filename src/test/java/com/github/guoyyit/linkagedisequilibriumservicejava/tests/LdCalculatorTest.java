package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdCalculatorTest {

    private final DefaultLdCalculator calculator = new DefaultLdCalculator();

    static Variant v(String id, long pos) {
        return new Variant(id, "1", pos, "A", "T");
    }

    static DatasetSnapshot snapshot(Map<String, int[]> columns, List<Variant> variants) {
        List<String> samples = List.of("s0", "s1", "s2", "s3");
        Map<String, Map<String, Integer>> matrix = new java.util.HashMap<>();
        for (String s : samples) {
            Map<String, Integer> row = new java.util.HashMap<>();
            int idx = samples.indexOf(s);
            for (Map.Entry<String, int[]> e : columns.entrySet()) {
                row.put(e.getKey(), e.getValue()[idx]);
            }
            matrix.put(s, row);
        }
        return new DatasetSnapshot(1L, List.of("b"), samples, variants, matrix);
    }

    @Test
    void perfectLinkageGivesR2OneAndZeroFreqDiff() {
        // A 与 B 剂量完全一致：[0,1,1,2]，p=0.5
        int[] g = {0, 1, 1, 2};
        DatasetSnapshot snap = snapshot(Map.of("A", g, "B", g.clone()),
                List.of(v("A", 1), v("B", 2)));
        LdPair pair = calculator.pair(snap, "A", "B");
        assertEquals(1.0, pair.rSquared(), 1e-12);
        assertEquals(0.0, pair.alleleFreqDiff(), 1e-12);
        assertEquals(4, pair.n());
    }

    @Test
    void knownR2Value() {
        // A=[0,1,1,2], C=[0,0,2,2] => r^2 = 0.5, 频率相同
        DatasetSnapshot snap = snapshot(
                Map.of("A", new int[]{0, 1, 1, 2}, "C", new int[]{0, 0, 2, 2}),
                List.of(v("A", 1), v("C", 3)));
        LdPair pair = calculator.pair(snap, "A", "C");
        assertEquals(0.5, pair.rSquared(), 1e-12);
        assertEquals(0.0, pair.alleleFreqDiff(), 1e-12);
    }

    @Test
    void alleleFrequencyDifference() {
        // A=[0,1,1,2] p=0.5 ; D=[2,2,2,2] p=1.0 => diff=0.5
        DatasetSnapshot snap = snapshot(
                Map.of("A", new int[]{0, 1, 1, 2}, "D", new int[]{2, 2, 2, 2}),
                List.of(v("A", 1), v("D", 4)));
        LdPair pair = calculator.pair(snap, "A", "D");
        assertEquals(0.5, pair.alleleFreqDiff(), 1e-12);
    }

    @Test
    void pairwiseCompleteMissingExclusion() {
        // 与 A 相同，但 s3 缺失：成对可用样本 3 个，仍完全相关
        DatasetSnapshot full = snapshot(
                Map.of("A", new int[]{0, 1, 1, 2}, "E", new int[]{0, 1, 1, 2}),
                List.of(v("A", 1), v("E", 5)));
        LdPair p1 = calculator.pair(full, "A", "E");

        DatasetSnapshot missing = snapshot(
                Map.of("A", new int[]{0, 1, 1, 2}, "E", new int[]{0, 1, 1, -1}),
                List.of(v("A", 1), v("E", 5)));
        LdPair p2 = calculator.pair(missing, "A", "E");

        assertEquals(1.0, p2.rSquared(), 1e-12);
        assertEquals(3, p2.n());
        // 缺失被成对排除，指标等价于在剩余样本上全量重算
        assertEquals(p1.rSquared(), p2.rSquared(), 1e-12);
    }

    @Test
    void computeAllMatchesPairwiseAndIsDeterministic() {
        DatasetSnapshot snap = snapshot(
                Map.of("A", new int[]{0, 1, 1, 2},
                        "B", new int[]{0, 1, 1, 2},
                        "C", new int[]{0, 0, 2, 2}),
                List.of(v("A", 1), v("B", 2), v("C", 3)));
        List<LdPair> first = calculator.computeAll(snap, List.of("C", "A", "B"),
                p -> { }, () -> false);
        List<LdPair> second = calculator.computeAll(snap, List.of("B", "A", "C"),
                p -> { }, () -> false);

        assertEquals(3, first.size());
        // 确定顺序（无序输入也按标识排序），两次结果逐项一致
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).variant1(), second.get(i).variant1());
            assertEquals(first.get(i).variant2(), second.get(i).variant2());
            assertEquals(first.get(i).rSquared(), second.get(i).rSquared(), 0.0);
        }
        LdPair ab = first.stream().filter(p -> p.variant2().equals("B")).findFirst().orElseThrow();
        assertEquals(calculator.pair(snap, "A", "B").rSquared(), ab.rSquared(), 0.0);
        // AB 完全连锁，r^2 = 1
        assertTrue(ab.rSquared() > 1 - 1e-12);
    }
}
