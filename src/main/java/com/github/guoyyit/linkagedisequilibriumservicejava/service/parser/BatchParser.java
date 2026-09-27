package com.github.guoyyit.linkagedisequilibriumservicejava.service.parser;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;

import java.io.IOException;
import java.io.Reader;

/** Parses in-memory requests and TSV streams into validated-shape batch objects. */
public interface BatchParser {

    CommittedBatch parseInMemory(BatchImportRequest request);

    CommittedBatch parseTsv(String batchId, Reader reader) throws IOException;
}
