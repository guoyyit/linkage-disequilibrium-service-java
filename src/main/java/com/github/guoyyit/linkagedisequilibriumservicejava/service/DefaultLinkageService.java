package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportBatchRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportResult;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.SnapshotView;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.VariantDto;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.JobStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 服务门面，协调 校验 -> 存储 -> 计算/筛选/任务。
 * 所有只读入口都先固定一个 {@link DatasetSnapshot}，保证结果属于某个已提交状态。
 */
@Service
public class DefaultLinkageService implements LinkageService {

    private final DatasetStore store;
    private final BatchValidationService validationService;
    private final VariantFilterService filterService;
    private final LdJobService ldJobService;

    @Value("${ld.job.default-timeout-ms:600000}")
    private long defaultJobTimeoutMillis;

    public DefaultLinkageService(DatasetStore store,
                                 BatchValidationService validationService,
                                 VariantFilterService filterService,
                                 LdJobService ldJobService) {
        this.store = store;
        this.validationService = validationService;
        this.filterService = filterService;
        this.ldJobService = ldJobService;
    }

    @Override
    public ImportResult importBatch(ImportBatchRequest request) {
        if (request == null) {
            throw new BatchRejectedException("?", List.of("请求体为空"));
        }
        // 重复导入：在任何校验之前短路，绝不改变状态
        if (request.batchId() != null && store.containsBatch(request.batchId())) {
            DatasetSnapshot snap = store.current();
            return new ImportResult(request.batchId(), true, snap.version(),
                    snap.sampleIds().size(), snap.variants().size(), List.of());
        }
        GenotypeBatch raw = toBatch(request);
        GenotypeBatch normalized = validationService.validate(raw, request.filterOptions());
        validationService.validateAgainst(normalized, store.current());
        DatasetSnapshot snap = store.commit(normalized);
        return new ImportResult(request.batchId(), false, snap.version(),
                snap.sampleIds().size(), snap.variants().size(), List.of());
    }

    @Override
    public FilterResult filter(FilterCriteria criteria) {
        return filterService.filter(criteria == null ? new FilterCriteria(
                null, null, null, null, null, null, null) : criteria);
    }

    @Override
    public LdJobState submitLdJob(Long snapshotVersion, List<String> variantIds, Long timeoutMillis) {
        DatasetSnapshot snap = snapshotVersion == null
                ? requireCurrent()
                : store.byVersion(snapshotVersion);
        List<String> ids = (variantIds == null || variantIds.isEmpty())
                ? snap.variants().stream().map(Variant::id).toList()
                : List.copyOf(variantIds);
        long timeout = timeoutMillis == null ? defaultJobTimeoutMillis : timeoutMillis;
        return ldJobService.submit(snap.version(), ids, timeout);
    }

    @Override
    public Optional<LdJobState> jobStatus(String jobId) {
        return ldJobService.get(jobId);
    }

    @Override
    public JobStatus cancelJob(String jobId) {
        return ldJobService.cancel(jobId);
    }

    @Override
    public Optional<List<LdPair>> jobResults(String jobId) {
        return ldJobService.results(jobId);
    }

    @Override
    public Optional<SnapshotView> currentSnapshot() {
        DatasetSnapshot snap = store.current();
        return Optional.ofNullable(snap).map(DefaultLinkageService::toView);
    }

    @Override
    public SnapshotView snapshot(long version) {
        return toView(store.byVersion(version));
    }

    private DatasetSnapshot requireCurrent() {
        DatasetSnapshot snap = store.current();
        if (snap == null) {
            throw new SnapshotNotFoundException(0);
        }
        return snap;
    }

    static SnapshotView toView(DatasetSnapshot snap) {
        return new SnapshotView(snap.version(), snap.batchIds(),
                snap.sampleIds().size(), snap.variants().size());
    }

    /** 请求 DTO -> 领域批次（不做规范化，交由校验层）。 */
    static GenotypeBatch toBatch(ImportBatchRequest request) {
        List<Variant> variants = new ArrayList<>();
        if (request.variants() != null) {
            for (VariantDto dto : request.variants()) {
                variants.add(toVariant(dto));
            }
        }
        List<List<Integer>> genotypes = request.genotypes() == null
                ? null : new ArrayList<>(request.genotypes());
        return new GenotypeBatch(request.batchId(),
                request.sampleIds() == null ? null : new ArrayList<>(request.sampleIds()),
                variants, genotypes);
    }

    static Variant toVariant(VariantDto dto) {
        if (dto == null) {
            return null;
        }
        long pos = dto.position() == null ? 0L : dto.position();
        return new Variant(dto.id(), dto.chrom(), pos, dto.ref(), dto.alt());
    }
}
