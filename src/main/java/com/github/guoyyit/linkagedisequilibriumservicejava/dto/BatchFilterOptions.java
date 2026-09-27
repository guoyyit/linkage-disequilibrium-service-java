package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

/**
 * 导入前对单个批次应用的位点/样本质量过滤阈值。
 *
 * @param minVariantMaf    位点最小次等位基因频率（低于则拒绝该批次）
 * @param maxMissingRate   位点与样本允许的最大基因型缺失比例（超过则拒绝）
 * @param minPosition      可选，位点位置下界（含）
 * @param maxPosition      可选，位点位置上界（含）
 */
public record BatchFilterOptions(Double minVariantMaf,
                                 Double maxMissingRate,
                                 Long minPosition,
                                 Long maxPosition) {
}
