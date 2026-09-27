package com.github.guoyyit.linkagedisequilibriumservicejava.service.importing;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;

import java.io.Reader;

/** Idempotent batch import orchestration. */
public interface ImportService {

    BatchResult importInMemory(BatchImportRequest request);

    BatchResult importTsv(String batchId, Reader tsv);
}
