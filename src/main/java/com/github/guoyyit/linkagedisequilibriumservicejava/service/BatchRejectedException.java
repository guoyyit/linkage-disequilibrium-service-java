package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import java.util.List;

/**
 * 导入前校验未通过时抛出。错误列表中每项都可定位到批次/样本/位点。
 */
public class BatchRejectedException extends RuntimeException {

    private final String batchId;
    private final List<String> errors;

    public BatchRejectedException(String batchId, List<String> errors) {
        super("批次 " + batchId + " 被拒绝: " + String.join("; ", errors));
        this.batchId = batchId;
        this.errors = List.copyOf(errors);
    }

    public String batchId() { return batchId; }
    public List<String> errors() { return errors; }
}
