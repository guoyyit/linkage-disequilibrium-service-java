package com.github.guoyyit.linkagedisequilibriumservicejava.service.storage;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.VariantStat;

import java.util.List;
import java.util.Map;

/**
 * Immutable committed data. Readers observe whole snapshots only.
 */
public interface DataSnapshot {

    long version();

    List<String> batchIds();

    List<String> samples();

    List<Variant> variants();

    /** Ordered variant IDs per chromosome, for deterministic pairing. */
    Map<String, List<Variant>> variantsByChromosome();

    /**
     * Aligned dosage matrix: dosage[variantIndex][sampleIndex]; -1 missing.
     * Sample indices align with {@link #samples()}.
     */
    byte[][] dosageMatrix();

    int indexOfSample(String sample);

    int indexOfVariant(Variant variant);

    boolean containsBatch(String batchId);

    boolean containsVariant(Variant variant);

    Map<Variant, VariantStat> statistics();
}
