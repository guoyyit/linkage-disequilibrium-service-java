package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * A validated, immutable batch persisted on commit.
 * Dosage rows are aligned with {@code variants}; each row length equals samples size.
 * Values: 0/1/2 allele dosage, -1 missing.
 */
public record CommittedBatch(String batchId,
                             List<String> samples,
                             List<Variant> variants,
                             List<byte[]> genotypes,
                             ImportOptions options,
                             long committedAtMillis) {
}
