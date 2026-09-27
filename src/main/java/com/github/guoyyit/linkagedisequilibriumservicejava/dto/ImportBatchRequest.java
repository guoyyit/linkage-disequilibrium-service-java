package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

import java.util.List;

/**
 * 导入一个批次的请求。
 *
 * @param batchId       批次标识；重复导入相同 batchId 不得改变结果
 * @param sampleIds     批次内样本标识（有序，与 genotypes 行对应）
 * @param variants      批次内位点（有序，与 genotypes 列对应）
 * @param genotypes     基因型矩阵；行=样本，列=位点，值为 0/1/2 或 null/-1(缺失)
 * @param filterOptions 导入前校验/过滤阈值；为空时使用服务端默认值
 */
public record ImportBatchRequest(String batchId,
                                 List<String> sampleIds,
                                 List<VariantDto> variants,
                                 List<List<Integer>> genotypes,
                                 BatchFilterOptions filterOptions) {
}
