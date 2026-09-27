package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.JobStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;

import java.util.List;
import java.util.Optional;

/**
 * 长时 LD 计算任务的管理：提交（去重，同一快照的同一计算只执行一次）、
 * 进度查询、取消、超时；取消/失败不留下部分结果。
 */
public interface LdJobService {

    /** 为某个快照提交全量两两 LD 计算；相同请求复用同一任务。 */
    LdJobState submit(long snapshotVersion, List<String> variantIds, long timeoutMillis);

    Optional<LdJobState> get(String jobId);

    /** 请求取消；返回取消后的状态。 */
    JobStatus cancel(String jobId);

    /** 取已完成任务结果；未完成/取消/失败返回空。 */
    Optional<List<LdPair>> results(String jobId);
}
