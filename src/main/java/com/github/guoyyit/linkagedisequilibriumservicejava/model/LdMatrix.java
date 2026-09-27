package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.Map;

/**
 * Immutable LD result attached to a data snapshot version.
 * LD is only defined for variants on the same chromosome.
 */
public record LdMatrix(String taskId, long snapshotVersion, long windowBp,
                       Map<Variant, Map<Variant, LdPair>> pairs) {
}
