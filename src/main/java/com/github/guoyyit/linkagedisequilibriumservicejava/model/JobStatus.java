package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/** 长时任务生命周期状态。 */
public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    TIMEOUT
}
