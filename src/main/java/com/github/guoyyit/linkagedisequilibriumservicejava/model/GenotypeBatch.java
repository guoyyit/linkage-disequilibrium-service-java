package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * 一批带样本与位点的基因型数据（导入的原始载荷）。
 *
 * <p>基因型编码：0 = 参考等位基因纯合(AA)，1 = 杂合(Aa)，2 = 变异等位基因纯合(aa)，
 * -1 / null = 缺失。{@code genotypes} 行对应样本、列对应位点。
 */
public record GenotypeBatch(
        String batchId,
        List<String> sampleIds,
        List<Variant> variants,
        List<List<Integer>> genotypes) {
}
