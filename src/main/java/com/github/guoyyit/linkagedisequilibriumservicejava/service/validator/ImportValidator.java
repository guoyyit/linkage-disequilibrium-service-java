package com.github.guoyyit.linkagedisequilibriumservicejava.service.validator;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;

/** Validates a candidate batch on its own and against committed data. */
public interface ImportValidator {

    void validate(CommittedBatch candidate, SnapshotStore store);
}
