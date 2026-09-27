package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 默认位点筛选。
 *
 * <p>筛选开始时固定一个 {@link DatasetSnapshot}（指定版本或最新已提交版本），之后全部基于该
 * 不可变快照计算，因此条件不变则结果可复现，且与并发导入互不影响。顺序固定为
 * (染色体规范化, 位置, 标识)，LD 剪枝为确定性贪心：候选位点按序保留，当且仅当它与所有已保留
 * 位点的 r² 均不超过 maxLdR2。
 */
@Service
public class DefaultVariantFilterService implements VariantFilterService {

    private static final int MISSING = -1;

    private final DatasetStore store;
    private final LdCalculator ldCalculator;

    public DefaultVariantFilterService(DatasetStore store, LdCalculator ldCalculator) {
        this.store = store;
        this.ldCalculator = ldCalculator;
    }

    @Override
    public FilterResult filter(FilterCriteria criteria) {
        DatasetSnapshot snapshot = criteria.snapshotVersion() == null
                ? store.current()
                : store.byVersion(criteria.snapshotVersion());
        if (snapshot == null) {
            return new FilterResult(0L, List.of());
        }
        validate(criteria);

        String chrom = criteria.chrom() == null ? null
                : DefaultBatchValidationService.canonicalChrom(criteria.chrom());
        List<Variant> candidates = new ArrayList<>();
        for (Variant v : snapshot.variants()) {
            if (chrom != null
                    && !DefaultBatchValidationService.canonicalChrom(v.chrom()).equals(chrom)) {
                continue;
            }
            if (criteria.windowStart() != null && v.position() < criteria.windowStart()) {
                continue;
            }
            if (criteria.windowEnd() != null && v.position() > criteria.windowEnd()) {
                continue;
            }
            double missing = missingRate(snapshot, v.id());
            if (criteria.maxMissingRate() != null
                    && missing > criteria.maxMissingRate() + 1e-12) {
                continue;
            }
            double maf = maf(snapshot, v.id());
            if (criteria.minMaf() != null && maf + 1e-12 < criteria.minMaf()) {
                continue;
            }
            candidates.add(v);
        }
        candidates.sort(Comparator
                .comparing((Variant v) -> DefaultBatchValidationService.canonicalChrom(v.chrom()))
                .thenComparingLong(Variant::position)
                .thenComparing(Variant::id));

        List<String> retained;
        if (criteria.maxLdR2() == null) {
            retained = candidates.stream().map(Variant::id).toList();
        } else {
            retained = pruneByLd(snapshot, candidates, criteria.maxLdR2());
        }
        return new FilterResult(snapshot.version(), retained);
    }

    /**
     * 确定性贪心 LD 剪枝：顺序遍历候选，仅当与所有已保留位点 r² ≤ 阈值才保留。
     * 每对 r² 由无状态计算器在同一快照上算出，与全量重算一致。
     */
    private List<String> pruneByLd(DatasetSnapshot snapshot, List<Variant> candidates,
                                   double maxR2) {
        List<String> kept = new ArrayList<>();
        for (Variant candidate : candidates) {
            boolean keep = true;
            for (String keptId : kept) {
                double r2 = ldCalculator.pair(snapshot, candidate.id(), keptId).rSquared();
                if (r2 > maxR2 + 1e-12) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                kept.add(candidate.id());
            }
        }
        return kept;
    }

    private static double missingRate(DatasetSnapshot snapshot, String variantId) {
        int total = snapshot.sampleIds().size();
        if (total == 0) {
            return 0.0;
        }
        int missing = 0;
        for (String s : snapshot.sampleIds()) {
            Map<String, Integer> row = snapshot.matrix().get(s);
            Integer g = row == null ? null : row.get(variantId);
            if (g == null || g == MISSING) {
                missing++;
            }
        }
        return (double) missing / total;
    }

    private static double maf(DatasetSnapshot snapshot, String variantId) {
        int called = 0;
        int dose = 0;
        for (String s : snapshot.sampleIds()) {
            Map<String, Integer> row = snapshot.matrix().get(s);
            Integer g = row == null ? null : row.get(variantId);
            if (g != null && g != MISSING) {
                called++;
                dose += g;
            }
        }
        if (called == 0) {
            return 0.0;
        }
        double af = dose / (2.0 * called);
        return Math.min(af, 1.0 - af);
    }

    private static void validate(FilterCriteria c) {
        List<String> errors = new ArrayList<>();
        if (c.minMaf() != null && (c.minMaf() < 0 || c.minMaf() > 0.5)) {
            errors.add("minMaf 必须位于 [0,0.5]");
        }
        if (c.maxMissingRate() != null && (c.maxMissingRate() < 0 || c.maxMissingRate() > 1)) {
            errors.add("maxMissingRate 必须位于 [0,1]");
        }
        if (c.maxLdR2() != null && (c.maxLdR2() < 0 || c.maxLdR2() > 1)) {
            errors.add("maxLdR2 必须位于 [0,1]");
        }
        if (c.windowStart() != null && c.windowEnd() != null
                && c.windowStart() > c.windowEnd()) {
            errors.add("位置窗口非法: start > end");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("筛选条件非法: " + String.join("; ", errors));
        }
    }
}
