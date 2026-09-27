package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * Outcome of a filter request: the selected variants and their statistics.
 */
public record FilterResponse(long snapshotVersion, String ldTaskId, LdFilterCriteria criteria,
                             List<VariantStat> variants) {
}
