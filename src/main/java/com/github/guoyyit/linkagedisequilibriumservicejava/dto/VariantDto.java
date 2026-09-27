package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

/** 位点的传输对象。 */
public record VariantDto(String id, String chrom, Long position, String ref, String alt) {
}
