package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * Validation rules applied to a batch before commit.
 *
 * @param allowedChromosomes when non-empty, variants on other chromosomes are rejected
 * @param minPosition        inclusive lower bound of accepted position (0 disables)
 * @param maxPosition        inclusive upper bound of accepted position (0 disables)
 * @param sampleMissingThreshold maximum fraction of missing genotypes allowed for a sample
 * @param variantMissingThreshold maximum fraction of missing genotypes allowed for a variant
 */
public record ImportOptions(List<String> allowedChromosomes,
                            long minPosition,
                            long maxPosition,
                            double sampleMissingThreshold,
                            double variantMissingThreshold) {

    public ImportOptions {
        allowedChromosomes = allowedChromosomes == null ? List.of() : List.copyOf(allowedChromosomes);
        if (sampleMissingThreshold < 0 || sampleMissingThreshold > 1) {
            throw new IllegalArgumentException("sampleMissingThreshold must be within [0,1]");
        }
        if (variantMissingThreshold < 0 || variantMissingThreshold > 1) {
            throw new IllegalArgumentException("variantMissingThreshold must be within [0,1]");
        }
    }

    public static ImportOptions defaults() {
        return new ImportOptions(List.of(), 0, 0, 0.1, 0.1);
    }
}
