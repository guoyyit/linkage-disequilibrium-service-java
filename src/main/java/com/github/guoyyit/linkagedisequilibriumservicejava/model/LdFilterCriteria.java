package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Deterministic variant selection criteria. A zero/empty value disables that rule.
 *
 * @param chromosome       restrict to one chromosome; null/blank disables
 * @param start            inclusive window start (0 disables)
 * @param end              inclusive window end (0 disables)
 * @param minMaf           minimum minor allele frequency
 * @param maxMissing       maximum per-variant missing rate
 * @param maxLdR2          if set (0 &lt; x &le; 1), greedy LD pruning removes later variants
 *                         correlated above this threshold within windowBp
 * @param windowBp         pruning/physical window; 0 means whole chromosome
 * @param ldTaskId         task producing the LD matrix used for pruning; null uses latest
 */
public record LdFilterCriteria(String chromosome, long start, long end, double minMaf,
                               double maxMissing, double maxLdR2, long windowBp, String ldTaskId) {

    public static LdFilterCriteria none() {
        return new LdFilterCriteria(null, 0, 0, 0.0, 1.0, 0.0, 0, null);
    }
}
