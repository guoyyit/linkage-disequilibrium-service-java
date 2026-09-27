package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * 一个双等位基因变异位点。
 *
 * @param id       位点唯一标识
 * @param chrom    染色体（不含 "chr" 前缀亦可，原样保留并规范化比较）
 * @param position 1-based 物理位置
 * @param ref      参考等位基因
 * @param alt      变异等位基因
 */
public record Variant(String id, String chrom, long position, String ref, String alt) {
}
