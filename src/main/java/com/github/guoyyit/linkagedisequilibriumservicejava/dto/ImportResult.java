package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

import java.util.List;

/**
 * 批次导入结果。
 *
 * @param batchId       批次标识
 * @param alreadyPresent true 表示该批次此前已提交，本次为幂等重复导入
 * @param snapshotVersion 导入后数据集快照版本
 * @param sampleCount   快照总样本数
 * @param variantCount  快照总位点数
 * @param rejectedVariants 被过滤拒绝的位点（仅在显式模式下使用；默认拒绝整个批次）
 */
public record ImportResult(String batchId,
                           boolean alreadyPresent,
                           long snapshotVersion,
                           int sampleCount,
                           int variantCount,
                           List<String> rejectedVariants) {
}
