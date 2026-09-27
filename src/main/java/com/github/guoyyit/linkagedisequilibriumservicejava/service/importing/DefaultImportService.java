package com.github.guoyyit.linkagedisequilibriumservicejava.service.importing;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchState;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ErrorLocation;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.parser.BatchParser;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.validator.ImportValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotent import orchestration.
 *
 * <p>Requests carrying the same {@code batchId} are serialized by a per-id
 * lock. If the batch is already committed, the re-submitted payload must be
 * identical (samples, variants, genotypes); then the existing commit is
 * returned with {@code duplicated=true} without touching data. A same-id
 * batch with different content is rejected as a conflict.
 */
@Service
public class DefaultImportService implements ImportService {

    private static final Logger log = LoggerFactory.getLogger(DefaultImportService.class);

    private final BatchParser parser;
    private final ImportValidator validator;
    private final SnapshotStore store;
    private final ConcurrentHashMap<String, Object> batchLocks = new ConcurrentHashMap<>();

    public DefaultImportService(BatchParser parser, ImportValidator validator, SnapshotStore store) {
        this.parser = parser;
        this.validator = validator;
        this.store = store;
    }

    @Override
    public BatchResult importInMemory(BatchImportRequest request) {
        if (request == null || request.batchId() == null || request.batchId().isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        CommittedBatch parsed = parser.parseInMemory(request);
        return doImport(parsed);
    }

    @Override
    public BatchResult importTsv(String batchId, Reader tsv) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        try {
            CommittedBatch parsed = parser.parseTsv(batchId, tsv);
            return doImport(parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading batch " + batchId, e);
        }
    }

    private BatchResult doImport(CommittedBatch parsed) {
        String batchId = parsed.batchId();
        Object lock = batchLocks.computeIfAbsent(batchId, k -> new Object());
        synchronized (lock) {
            var existing = store.batch(batchId);
            if (existing.isPresent()) {
                if (!sameContent(existing.get(), parsed)) {
                    throw new ConflictException("batch " + batchId
                            + " already committed with different content");
                }
                log.debug("duplicate import of batch {} ignored", batchId);
                return new BatchResult(batchId, BatchState.COMMITTED, true,
                        existing.get().variants().size(), existing.get().samples().size(), List.of());
            }
            validator.validate(parsed, store);
            store.commit(parsed);
            log.info("committed batch {} ({} variants, {} samples), snapshot version {}",
                    batchId, parsed.variants().size(), parsed.samples().size(),
                    store.current().version());
            return new BatchResult(batchId, BatchState.COMMITTED, false,
                    parsed.variants().size(), parsed.samples().size(), List.of());
        }
    }

    private boolean sameContent(CommittedBatch a, CommittedBatch b) {
        return Objects.equals(a.samples(), b.samples())
                && Objects.equals(a.variants(), b.variants())
                && genotypesEqual(a.genotypes(), b.genotypes());
    }

    private boolean genotypesEqual(List<byte[]> x, List<byte[]> y) {
        if (x.size() != y.size()) {
            return false;
        }
        for (int r = 0; r < x.size(); r++) {
            if (!java.util.Arrays.equals(x.get(r), y.get(r))) {
                return false;
            }
        }
        return true;
    }
}
