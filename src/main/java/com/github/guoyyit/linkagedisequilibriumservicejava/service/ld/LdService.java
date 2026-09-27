package com.github.guoyyit.linkagedisequilibriumservicejava.service.ld;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdComputeRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;

/** Ensures LD matrices are computed exactly once per (snapshot, window). */
public interface LdService {

    TaskInfo start(LdComputeRequest request);

    TaskInfo status(String taskId);

    boolean cancel(String taskId);

    LdMatrix result(String taskId);

    /** Returns a completed matrix for the criteria, starting computation if needed. */
    LdMatrix ensureMatrix(String preferredTaskId, long windowBp, long timeoutMillis);
}
