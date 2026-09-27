package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.TaskCancelledException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ImportOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ld.DefaultLdCalculator;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.ImmutableSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdCalculatorTest {

    private static final TaskContext NOOP = new TaskContext() {
        @Override public void progress(int percent) {
        }
        @Override public boolean isCancelled() {
            return false;
        }
        @Override public void checkpoint() {
        }
    };

    private DataSnapshot snapshot(List<Variant> variants, List<byte[]> rows) {
        CommittedBatch b = new CommittedBatch("b",
                List.of("s1", "s2", "s3", "s4"),
                variants, rows, ImportOptions.defaults(), 1L);
        return ImmutableSnapshot.fromBatches(List.of(b));
    }

    @Test
    void perfectlyCorrelatedDosagesHaveR2One() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 20, "A", "T");
        // identical dosage vectors (0,1,1,2)
        DataSnapshot snap = snapshot(List.of(v1, v2),
                List.of(new byte[]{0, 1, 1, 2}, new byte[]{0, 1, 1, 2}));

        LdMatrix matrix = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        LdPair pair = matrix.pairs().get(v1).get(v2);
        assertEquals(1.0, pair.rSquared(), 1e-9);
        assertEquals(0.0, pair.mafDifference(), 1e-9);
    }

    @Test
    void independentDosagesHaveLowerR2() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 20, "A", "T");
        DataSnapshot snap = snapshot(List.of(v1, v2),
                List.of(new byte[]{0, 0, 2, 2}, new byte[]{0, 2, 0, 2}));
        LdMatrix matrix = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        double r2 = matrix.pairs().get(v1).get(v2).rSquared();
        assertEquals(0.0, r2, 1e-9);
    }

    @Test
    void mafDifferenceIsAbsolute() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 20, "A", "T");
        // v1 alt copies 0 -> af 0, maf 0; v2 all 2 -> af 1, maf 0; both mono
        DataSnapshot snap = snapshot(List.of(v1, v2),
                List.of(new byte[]{0, 0, 0, 0}, new byte[]{2, 2, 2, 2}));
        LdMatrix matrix = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        // both monomorphic: r2 = 0
        assertEquals(0.0, matrix.pairs().get(v1).get(v2).rSquared(), 1e-9);
    }

    @Test
    void windowExcludesDistantPairs() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 2000, "A", "T");
        DataSnapshot snap = snapshot(List.of(v1, v2),
                List.of(new byte[]{0, 1, 1, 2}, new byte[]{0, 1, 1, 2}));
        LdMatrix matrix = new DefaultLdCalculator().calculate("t", snap, 500, NOOP);
        assertNull(matrix.pairs().get(v1));
    }

    @Test
    void missingSamplesArePairwiseExcluded() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 20, "A", "T");
        // only overlap s1..s3; within overlap identical
        DataSnapshot snap = snapshot(List.of(v1, v2),
                List.of(new byte[]{0, 1, 2, -1}, new byte[]{0, 1, -1, 2}));
        LdMatrix matrix = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        LdPair pair = matrix.pairs().get(v1).get(v2);
        assertEquals(1.0, pair.rSquared(), 1e-9);
        assertEquals(1, matrix.snapshotVersion());
    }

    @Test
    void computationSpansSameCommittedVersionAsFullRecompute() {
        Variant v1 = new Variant("chr1", 10, "A", "T");
        Variant v2 = new Variant("chr1", 20, "A", "T");
        Variant v3 = new Variant("chr1", 30, "A", "T");
        DataSnapshot snap = snapshot(List.of(v1, v2, v3),
                List.of(new byte[]{0, 1, 1, 2}, new byte[]{0, 1, 1, 2},
                        new byte[]{2, 1, 1, 0}));
        LdMatrix a = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        LdMatrix b = new DefaultLdCalculator().calculate("t", snap, 0, NOOP);
        for (Variant x : snap.variants()) {
            for (Variant y : snap.variants()) {
                if (!x.equals(y)) {
                    assertEquals(a.pairs().get(x).get(y).rSquared(),
                            b.pairs().get(x).get(y).rSquared(), 1e-12);
                }
            }
        }
        assertTrue(a.pairs().get(v1).containsKey(v3));
    }
}
