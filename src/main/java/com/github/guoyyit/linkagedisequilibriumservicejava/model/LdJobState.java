package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;

import java.util.List;

/**
 * 一个 LD 计算任务的完整状态（任务服务内部持有）。
 * 状态发布规则：仅在 COMPLETED 时才设置 results；CANCELLED/FAILED/TIMEOUT 永不暴露部分结果。
 */
public final class LdJobState {

    private final String jobId;
    private final long snapshotVersion;
    private final List<String> variantIds;
    private final long timeoutMillis;
    private volatile JobStatus status;
    private volatile int progressPercent;
    private volatile List<LdPair> results;
    private volatile String error;

    public LdJobState(String jobId, long snapshotVersion, List<String> variantIds,
                      long timeoutMillis) {
        this.jobId = jobId;
        this.snapshotVersion = snapshotVersion;
        this.variantIds = List.copyOf(variantIds);
        this.timeoutMillis = timeoutMillis;
        this.status = JobStatus.PENDING;
    }

    public String jobId() { return jobId; }
    public long snapshotVersion() { return snapshotVersion; }
    public List<String> variantIds() { return variantIds; }
    public long timeoutMillis() { return timeoutMillis; }
    public JobStatus status() { return status; }
    public int progressPercent() { return progressPercent; }
    public List<LdPair> results() { return results; }
    public String error() { return error; }

    public boolean isTerminal() {
        return status == JobStatus.COMPLETED || status == JobStatus.CANCELLED
                || status == JobStatus.FAILED || status == JobStatus.TIMEOUT;
    }

    public void running() {
        this.status = JobStatus.RUNNING;
    }

    public void progress(int percent) {
        this.progressPercent = Math.max(this.progressPercent, Math.min(99, percent));
    }

    public void completed(List<LdPair> results) {
        this.results = List.copyOf(results);
        this.progressPercent = 100;
        this.status = JobStatus.COMPLETED;
    }

    public void cancelled() {
        this.results = null;
        this.status = JobStatus.CANCELLED;
    }

    public void failed(String error) {
        this.results = null;
        this.error = error;
        this.status = JobStatus.FAILED;
    }

    public void timeout() {
        this.results = null;
        this.status = JobStatus.TIMEOUT;
    }
}
