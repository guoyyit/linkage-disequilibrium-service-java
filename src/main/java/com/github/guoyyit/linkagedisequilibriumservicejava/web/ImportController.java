package com.github.guoyyit.linkagedisequilibriumservicejava.web;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchImportRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchSummary;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.SnapshotInfo;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.importing.ImportService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Batch import, batch listing and snapshot inspection. */
@RestController
@RequestMapping("/api")
public class ImportController {

    private final ImportService importService;
    private final SnapshotStore store;

    public ImportController(ImportService importService, SnapshotStore store) {
        this.importService = importService;
        this.store = store;
    }

    /** In-memory batch (JSON). Genotypes use dosage 0/1/2 and -1 for missing. */
    @PostMapping(path = "/batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BatchResult importJson(@RequestBody BatchImportRequest request) {
        return importService.importInMemory(request);
    }

    /** Local file upload in TSV format; {@code batchId} is the idempotency key. */
    @PostMapping(path = "/batches/{batchId}/tsv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BatchResult importTsv(@PathVariable String batchId,
                                 @RequestParam("file") MultipartFile file) throws IOException {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return importService.importTsv(batchId, reader);
        }
    }

    @GetMapping("/batches")
    public List<BatchSummary> listBatches() {
        return store.batches();
    }

    @GetMapping("/snapshot")
    public SnapshotInfo snapshot() {
        var s = store.current();
        return new SnapshotInfo(s.version(), s.batchIds().size(),
                s.samples().size(), s.variants().size());
    }
}
