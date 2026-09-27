package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Linkage disequilibrium measures for an unordered pair of variants.
 *
 * @param mafDifference absolute difference of minor allele frequencies
 * @param rSquared      squared genotype correlation coefficient
 */
public record LdPair(Variant first, Variant second, double mafDifference, double rSquared) {
}
