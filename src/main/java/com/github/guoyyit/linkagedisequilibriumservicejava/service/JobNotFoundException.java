package com.github.guoyyit.linkagedisequilibriumservicejava.service;

/** 查询或操作的任务不存在。 */
public class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobId) {
        super("任务不存在: " + jobId);
    }
}
