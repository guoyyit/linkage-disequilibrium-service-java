package com.github.guoyyit.linkagedisequilibriumservicejava.service.storage;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.VariantStat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable merged view of committed batches.
 *
 * <p>Samples and variants are ordered by first appearance in commit order;
 * cells never supplied by any batch are treated as missing. Statistics are
 * computed once at construction so every reader sees identical numbers.
 */
public final class ImmutableSnapshot implements DataSnapshot {

    private static final byte UNSEEN = Byte.MIN_VALUE;

    private final long version;
    private final List<String> batchIds;
    private final List<String> samples;
    private final List<Variant> variants;
    private final Map<String, Integer> sampleIndex;
    private final Map<Variant, Integer> variantIndex;
    private final Map<String, List<Variant>> byChromosome;
    private final byte[][] matrix;
    private final Map<Variant, VariantStat> statistics;

    private ImmutableSnapshot(long version, List<String> batchIds, List<String> samples,
                              List<Variant> variants, Map<String, Integer> sampleIndex,
                              Map<Variant, Integer> variantIndex,
                              Map<String, List<Variant>> byChromosome,
                              byte[][] matrix, Map<Variant, VariantStat> statistics) {
        this.version = version;
        this.batchIds = batchIds;
        this.samples = samples;
        this.variants = variants;
        this.sampleIndex = sampleIndex;
        this.variantIndex = variantIndex;
        this.byChromosome = byChromosome;
        this.matrix = matrix;
        this.statistics = statistics;
    }

    /** Builds the first (empty) snapshot. */
    public static ImmutableSnapshot empty() {
        return new ImmutableSnapshot(0, List.of(), List.of(), List.of(),
                Map.of(), Map.of(), Map.of(), new byte[0][], Map.of());
    }

    /**
     * Merges one batch using first-write-wins semantics. The caller is
     * responsible for rejecting conflicting non-missing values; any conflict
     * encountered here throws {@link IllegalStateException}.
     */
    public ImmutableSnapshot append(CommittedBatch batch) {
        List<String> newSamples = new ArrayList<>(samples);
        Map<String, Integer> newSampleIndex = new HashMap<>(sampleIndex);
        for (String s : batch.samples()) {
            if (!newSampleIndex.containsKey(s)) {
                newSampleIndex.put(s, newSamples.size());
                newSamples.add(s);
            }
        }

        List<Variant> newVariants = new ArrayList<>(variants);
        Map<Variant, Integer> newVariantIndex = new HashMap<>(variantIndex);
        List<Integer> mappedRows = new ArrayList<>();
        for (Variant v : batch.variants()) {
            Integer idx = newVariantIndex.get(v);
            if (idx == null) {
                idx = newVariants.size();
                newVariantIndex.put(v, idx);
                newVariants.add(v);
            }
            mappedRows.add(idx);
        }

        int sampleCount = newSamples.size();
        byte[][] merged = new byte[newVariants.size()][sampleCount];
        for (byte[] row : merged) {
            java.util.Arrays.fill(row, UNSEEN);
        }
        for (int r = 0; r < variants.size(); r++) {
            System.arraycopy(matrix[r], 0, merged[r], 0, samples.size());
        }

        for (int br = 0; br < batch.variants().size(); br++) {
            int gr = mappedRows.get(br);
            byte[] batchRow = batch.genotypes().get(br);
            for (int bc = 0; bc < batch.samples().size(); bc++) {
                int gc = newSampleIndex.get(batch.samples().get(bc));
                byte value = batchRow[bc];
                byte existing = merged[gr][gc];
                // Missing means "unobserved": it never overwrites and never blocks
                // another batch; only distinct non-missing values are a conflict.
                if (existing != -1 && existing != UNSEEN) {
                    if (value != -1 && value != existing) {
                        throw new IllegalStateException("conflicting genotype for "
                                + batch.variants().get(br) + "/" + batch.samples().get(bc));
                    }
                } else if (value != -1) {
                    merged[gr][gc] = value;
                }
            }
        }

        List<String> newBatchIds = new ArrayList<>(batchIds);
        newBatchIds.add(batch.batchId());
        return build(version + 1, newBatchIds, newSamples, newVariants,
                newSampleIndex, newVariantIndex, merged);
    }

    private static ImmutableSnapshot build(long version, List<String> batchIds,
                                           List<String> samples, List<Variant> variants,
                                           Map<String, Integer> sampleIndex,
                                           Map<Variant, Integer> variantIndex,
                                           byte[][] merged) {
        for (byte[] row : merged) {
            for (int c = 0; c < row.length; c++) {
                if (row[c] == UNSEEN) {
                    row[c] = -1;
                }
            }
        }
        Map<String, List<Variant>> byChrom = new LinkedHashMap<>();
        for (Variant v : variants) {
            byChrom.computeIfAbsent(v.chromosome(), k -> new ArrayList<>()).add(v);
        }
        byChrom.replaceAll((k, list) -> {
            List<Variant> sorted = new ArrayList<>(list);
            Collections.sort(sorted);
            return List.copyOf(sorted);
        });

        Map<Variant, VariantStat> stats = new LinkedHashMap<>();
        int n = samples.size();
        for (int r = 0; r < variants.size(); r++) {
            Variant v = variants.get(r);
            long nonMissing = 0;
            long altCopies = 0;
            long missing = 0;
            for (int c = 0; c < n; c++) {
                byte d = merged[r][c];
                if (d < 0) {
                    missing++;
                } else {
                    nonMissing++;
                    altCopies += d;
                }
            }
            double af = nonMissing == 0 ? 0.0 : altCopies / (2.0 * nonMissing);
            double maf = Math.min(af, 1.0 - af);
            double missingRate = n == 0 ? 0.0 : (double) missing / n;
            stats.put(v, new VariantStat(v, af, maf, missingRate, nonMissing));
        }
        return new ImmutableSnapshot(version, List.copyOf(batchIds), List.copyOf(samples),
                List.copyOf(variants), Map.copyOf(sampleIndex), Map.copyOf(variantIndex),
                Collections.unmodifiableMap(byChrom), merged, Collections.unmodifiableMap(stats));
    }

    /** Rebuilds a snapshot from an ordered list of already committed batches. */
    public static ImmutableSnapshot fromBatches(List<CommittedBatch> orderedBatches) {
        ImmutableSnapshot snapshot = empty();
        for (CommittedBatch b : orderedBatches) {
            snapshot = snapshot.append(b);
        }
        return snapshot;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public List<String> batchIds() {
        return batchIds;
    }

    @Override
    public List<String> samples() {
        return samples;
    }

    @Override
    public List<Variant> variants() {
        return variants;
    }

    @Override
    public Map<String, List<Variant>> variantsByChromosome() {
        return byChromosome;
    }

    @Override
    public byte[][] dosageMatrix() {
        return matrix;
    }

    @Override
    public int indexOfSample(String sample) {
        return sampleIndex.getOrDefault(sample, -1);
    }

    @Override
    public int indexOfVariant(Variant variant) {
        return variantIndex.getOrDefault(variant, -1);
    }

    @Override
    public boolean containsBatch(String batchId) {
        return batchIds.contains(batchId);
    }

    @Override
    public boolean containsVariant(Variant variant) {
        return variantIndex.containsKey(variant);
    }

    @Override
    public Map<Variant, VariantStat> statistics() {
        return statistics;
    }
}
