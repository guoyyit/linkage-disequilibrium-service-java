package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

/**
 * 位点筛选条件组合。所有字段均可空，表示该维度不限制。
 *
 * @param chrom            仅保留该染色体
 * @param windowStart      位置窗口下界（含）
 * @param windowEnd        位置窗口上界（含）
 * @param minMaf           最小次等位基因频率
 * @param maxMissingRate   最大缺失率
 * @param maxLdR2          连锁强度阈值：对候选位点贪心剪枝，使保留位点两两 r^2 不超过该值
 * @param snapshotVersion  基于哪个快照版本筛选；为空则使用最新已提交快照
 */
public record FilterCriteria(String chrom,
                             Long windowStart,
                             Long windowEnd,
                             Double minMaf,
                             Double maxMissingRate,
                             Double maxLdR2,
                             Long snapshotVersion) {
}
