package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * Result of an import attempt.
 *
 * @param duplicated true if the batch was already known; data was not changed
 */
public record BatchResult(String batchId, BatchState state, boolean duplicated,
                          int variantCount, int sampleCount, List<ErrorLocation> errors) {
}
