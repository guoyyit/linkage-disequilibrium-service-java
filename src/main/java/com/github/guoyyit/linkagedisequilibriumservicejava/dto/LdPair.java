package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

/**
 * 一对位点间的连锁不平衡指标。
 *
 * @param variant1       位点 1 标识
 * @param variant2       位点 2 标识
 * @param alleleFreqDiff 等位基因频率差 |p1 - p2|
 * @param rSquared       基因型相关系数平方 r^2
 * @param n              用于计算的非缺失样本对数
 */
public record LdPair(String variant1,
                     String variant2,
                     double alleleFreqDiff,
                     double rSquared,
                     int n) {
}
