package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Metadata of the currently committed data snapshot.
 */
public record SnapshotInfo(long version, int batchCount, int sampleCount, int variantCount) {
}
