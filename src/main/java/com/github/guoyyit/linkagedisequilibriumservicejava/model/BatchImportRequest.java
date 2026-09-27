package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * One batch of genotype data supplied in memory.
 * Genotypes are encoded as allele dosage in {0,1,2}; -1 means missing.
 *
 * @param batchId   client supplied idempotency key
 * @param samples   sample identifiers, one per column of each row
 * @param variants  variant identifiers, one per row
 * @param genotypes genotype rows aligned with {@code variants}/{@code samples}
 */
public record BatchImportRequest(String batchId,
                                 List<String> samples,
                                 List<Variant> variants,
                                 List<List<Integer>> genotypes,
                                 ImportOptions options) {
}
