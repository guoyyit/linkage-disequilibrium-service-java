package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

import java.util.List;

/** 已提交数据集快照的元信息视图。 */
public record SnapshotView(long version,
                           List<String> batchIds,
                           int sampleCount,
                           int variantCount) {
}
