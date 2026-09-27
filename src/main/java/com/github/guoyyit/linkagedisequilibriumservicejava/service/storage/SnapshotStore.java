package com.github.guoyyit.linkagedisequilibriumservicejava.service.storage;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchSummary;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;

import java.util.List;
import java.util.Optional;

/** Atomic snapshot storage with persistent batch journal and crash recovery. */
public interface SnapshotStore {

    DataSnapshot current();

    /** Atomically validates against the current snapshot, persists and commits. */
    void commit(CommittedBatch batch);

    List<BatchSummary> batches();

    Optional<CommittedBatch> batch(String batchId);

    /** Reload committed batches from the journal directory. */
    void recover();
}
