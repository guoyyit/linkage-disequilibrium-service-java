package com.github.guoyyit.linkagedisequilibriumservicejava.service.ld;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.VariantStat;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic linkage disequilibrium calculator.
 *
 * <p>For each chromosome variants are processed in sorted (position, ref, alt)
 * order; pairs beyond {@code windowBp} (when positive) are skipped.
 *
 * <ul>
 *     <li>{@code rSquared} is the squared Pearson correlation of alternate
 *     allele dosages using pairwise-complete (non-missing in both rows) samples;
 *     constant rows yield 0.</li>
 *     <li>{@code mafDifference} is the absolute difference of the snapshot-wide
 *     minor allele frequencies, so it is independent of pairing.</li>
 * </ul>
 * Because every input is an immutable snapshot and iteration order is fixed,
 * the result is identical to a full recomputation on the same data.
 */
@Component
public class DefaultLdCalculator implements LdCalculator {

    private static final int MIN_PAIR_OVERLAP = 2;

    @Override
    public LdMatrix calculate(String taskId, DataSnapshot snapshot, long windowBp,
                              TaskContext context) {
        byte[][] matrix = snapshot.dosageMatrix();
        Map<Variant, VariantStat> stats = snapshot.statistics();
        Map<Variant, Map<Variant, LdPair>> result = new HashMap<>();

        long totalWork = estimateWork(snapshot, windowBp);
        long done = 0;
        long lastReported = -1;

        for (Map.Entry<String, List<Variant>> entry : snapshot.variantsByChromosome().entrySet()) {
            List<Variant> vars = entry.getValue();
            int n = vars.size();
            for (int i = 0; i < n; i++) {
                Variant a = vars.get(i);
                int ia = snapshot.indexOfVariant(a);
                byte[] rowA = matrix[ia];
                double mafA = stats.get(a).maf();
                for (int j = i + 1; j < n; j++) {
                    Variant b = vars.get(j);
                    long distance = b.position() - a.position();
                    if (windowBp > 0 && distance > windowBp) {
                        break;
                    }
                    context.checkpoint();
                    int ib = snapshot.indexOfVariant(b);
                    byte[] rowB = matrix[ib];
                    double r2 = pearsonSquared(rowA, rowB);
                    double mafDiff = Math.abs(mafA - stats.get(b).maf());
                    LdPair pair = new LdPair(a, b, mafDiff, r2);
                    result.computeIfAbsent(a, k -> new HashMap<>()).put(b, pair);
                    result.computeIfAbsent(b, k -> new HashMap<>()).put(a, pair);

                    done++;
                    if (totalWork > 0) {
                        long pct = (done * 100) / totalWork;
                        if (pct != lastReported) {
                            context.progress((int) pct);
                            lastReported = pct;
                        }
                    }
                }
            }
        }
        context.progress(100);
        return new LdMatrix(taskId, snapshot.version(), windowBp, deepUnmodifiable(result));
    }

    private static double pearsonSquared(byte[] a, byte[] b) {
        double sumX = 0;
        double sumY = 0;
        int n = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] >= 0 && b[k] >= 0) {
                sumX += a[k];
                sumY += b[k];
                n++;
            }
        }
        if (n < MIN_PAIR_OVERLAP) {
            return 0.0;
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] >= 0 && b[k] >= 0) {
                double dx = a[k] - meanX;
                double dy = b[k] - meanY;
                cov += dx * dy;
                varX += dx * dx;
                varY += dy * dy;
            }
        }
        if (varX <= 0 || varY <= 0) {
            return 0.0;
        }
        double r2 = (cov * cov) / (varX * varY);
        if (r2 < 0) {
            r2 = 0;
        }
        if (r2 > 1.0000000001) {
            r2 = Math.min(1.0, r2);
        }
        return r2;
    }

    private long estimateWork(DataSnapshot snapshot, long windowBp) {
        long work = 0;
        for (List<Variant> vars : snapshot.variantsByChromosome().values()) {
            int n = vars.size();
            if (windowBp <= 0) {
                work += (long) n * (n - 1) / 2;
                continue;
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (vars.get(j).position() - vars.get(i).position() > windowBp) {
                        break;
                    }
                    work++;
                }
            }
        }
        return work;
    }

    private Map<Variant, Map<Variant, LdPair>> deepUnmodifiable(
            Map<Variant, Map<Variant, LdPair>> source) {
        Map<Variant, Map<Variant, LdPair>> copy = new HashMap<>();
        for (var e : source.entrySet()) {
            copy.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Map.copyOf(copy);
    }
}
