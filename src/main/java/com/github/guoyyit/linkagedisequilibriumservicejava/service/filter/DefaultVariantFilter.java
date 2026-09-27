package com.github.guoyyit.linkagedisequilibriumservicejava.service.filter;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.FilterResponse;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdFilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.VariantStat;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ld.LdService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic variant selection.
 *
 * <p>All rules are evaluated against one immutable snapshot, so concurrent
 * imports never produce drifting results and identical criteria always yield
 * identical sets. When {@code maxLdR2} is active, variants are considered in
 * fixed (chromosome, position, ref, alt) order and greedily pruned: a variant
 * is removed if it correlates at {@code r2 > maxLdR2} with any already kept
 * variant within {@code windowBp}. Ties are impossible because the iteration
 * order is total.
 */
@Service
public class DefaultVariantFilter implements VariantFilter {

    private final SnapshotStore store;
    private final LdService ldService;

    public DefaultVariantFilter(SnapshotStore store, LdService ldService) {
        this.store = store;
        this.ldService = ldService;
    }

    @Override
    public FilterResponse select(LdFilterCriteria criteria) {
        DataSnapshot snapshot = store.current();
        Map<Variant, VariantStat> stats = snapshot.statistics();

        List<Variant> candidates = new ArrayList<>();
        for (Variant v : snapshot.variants()) {
            if (matches(criteria, v, stats.get(v))) {
                candidates.add(v);
            }
        }
        candidates.sort(Comparator.naturalOrder());

        String usedTaskId = null;
        if (criteria.maxLdR2() > 0 && criteria.maxLdR2() <= 1.0) {
            LdMatrix matrix = ldService.ensureMatrix(criteria.ldTaskId(),
                    Math.max(0, criteria.windowBp()), 0);
            if (matrix.snapshotVersion() != snapshot.version()) {
                // Defensive: ensureMatrix always uses the current snapshot, so
                // this can only happen with a stale explicit task id.
                throw new IllegalStateException("LD matrix version " + matrix.snapshotVersion()
                        + " does not match snapshot " + snapshot.version());
            }
            usedTaskId = matrix.taskId();
            candidates = prune(candidates, criteria.maxLdR2(), matrix);
        }

        List<VariantStat> result = new ArrayList<>();
        for (Variant v : candidates) {
            result.add(stats.get(v));
        }
        return new FilterResponse(snapshot.version(), usedTaskId, criteria, List.copyOf(result));
    }

    private boolean matches(LdFilterCriteria c, Variant v, VariantStat stat) {
        if (c.chromosome() != null && !c.chromosome().isBlank()
                && !c.chromosome().equals(v.chromosome())) {
            return false;
        }
        if (c.start() > 0 && v.position() < c.start()) {
            return false;
        }
        if (c.end() > 0 && v.position() > c.end()) {
            return false;
        }
        if (c.minMaf() > 0 && stat.maf() + 1e-12 < c.minMaf()) {
            return false;
        }
        if (c.maxMissing() < 1 && stat.missingRate() > c.maxMissing() + 1e-12) {
            return false;
        }
        return true;
    }

    private List<Variant> prune(List<Variant> ordered, double threshold, LdMatrix matrix) {
        List<Variant> kept = new ArrayList<>();
        Set<Variant> removed = new HashSet<>();
        for (Variant v : ordered) {
            boolean drop = false;
            for (Variant anchor : kept) {
                if (!anchor.chromosome().equals(v.chromosome())) {
                    continue;
                }
                var pair = lookup(matrix, anchor, v);
                if (pair == null) {
                    continue;
                }
                if (pair.rSquared() > threshold + 1e-12) {
                    drop = true;
                    break;
                }
            }
            if (!drop) {
                kept.add(v);
            } else {
                removed.add(v);
            }
        }
        return kept;
    }

    private com.github.guoyyit.linkagedisequilibriumservicejava.model.LdPair lookup(
            LdMatrix matrix, Variant a, Variant b) {
        Map<Variant, com.github.guoyyit.linkagedisequilibriumservicejava.model.LdPair> row =
                matrix.pairs().get(a);
        return row == null ? null : row.get(b);
    }
}
