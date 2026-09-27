package com.github.guoyyit.linkagedisequilibriumservicejava.model;

public record BatchSummary(String batchId, BatchState state, int variantCount,
                          int sampleCount, long committedAtMillis) {
}
