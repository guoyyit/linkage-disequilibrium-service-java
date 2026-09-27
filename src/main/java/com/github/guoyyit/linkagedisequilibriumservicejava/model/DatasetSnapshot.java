package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;
import java.util.Map;

/**
 * 已提交批次构成的数据集的不可变快照。
 *
 * <p>所有查询与计算都基于某个快照版本，保证读操作不会看到部分批次/部分位点写入的中间结果。
 */
public final class DatasetSnapshot {

    private final long version;
    private final List<String> batchIds;
    private final List<String> sampleIds;
    private final List<Variant> variants;
    /** 样本 -> 位点 -> 基因型（0/1/2，-1 表示缺失） */
    private final Map<String, Map<String, Integer>> matrix;

    public DatasetSnapshot(long version,
                           List<String> batchIds,
                           List<String> sampleIds,
                           List<Variant> variants,
                           Map<String, Map<String, Integer>> matrix) {
        this.version = version;
        this.batchIds = batchIds;
        this.sampleIds = sampleIds;
        this.variants = variants;
        this.matrix = matrix;
    }

    public long version() { return version; }
    public List<String> batchIds() { return batchIds; }
    public List<String> sampleIds() { return sampleIds; }
    public List<Variant> variants() { return variants; }
    public Map<String, Map<String, Integer>> matrix() { return matrix; }
}
