package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportBatchRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.SnapshotView;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.JobStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;

import java.util.List;
import java.util.Optional;

/** 对外门面：导入、筛选、LD 任务、快照查询。 */
public interface LinkageService {

    ImportResult importBatch(ImportBatchRequest request);

    FilterResult filter(FilterCriteria criteria);

    LdJobState submitLdJob(Long snapshotVersion, List<String> variantIds, Long timeoutMillis);

    Optional<LdJobState> jobStatus(String jobId);

    JobStatus cancelJob(String jobId);

    Optional<List<LdPair>> jobResults(String jobId);

    Optional<SnapshotView> currentSnapshot();

    SnapshotView snapshot(long version);
}
