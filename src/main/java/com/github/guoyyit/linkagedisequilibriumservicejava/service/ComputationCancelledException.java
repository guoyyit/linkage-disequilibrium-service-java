package com.github.guoyyit.linkagedisequilibriumservicejava.service;

/** 计算过程中被协作式取消。调用方不得保留部分结果。 */
public class ComputationCancelledException extends RuntimeException {
    public ComputationCancelledException(String message) {
        super(message);
    }
}
