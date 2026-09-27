package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Per-variant statistics computed from a snapshot.
 */
public record VariantStat(Variant variant, double alleleFrequency, double maf,
                          double missingRate, long nonMissingSamples) {
}
