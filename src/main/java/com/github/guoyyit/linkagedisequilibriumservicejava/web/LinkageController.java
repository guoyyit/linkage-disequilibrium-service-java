package com.github.guoyyit.linkagedisequilibriumservicejava.web;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ImportBatchRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.JobView;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.LinkageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地连锁不平衡服务 REST 接口。
 *
 * <pre>
 * POST /api/batches                 导入（提交）一个基因型批次（幂等）
 * POST /api/filter                  按组合条件筛选位点
 * POST /api/ld/jobs                 提交全量/部分位点的两两 LD 计算任务（幂等去重）
 * GET  /api/ld/jobs/{id}            查询进度
 * POST /api/ld/jobs/{id}/cancel     取消任务
 * GET  /api/ld/jobs/{id}/results    取完整结果（未完成/取消/失败/超时为空）
 * GET  /api/snapshot                当前已提交快照
 * GET  /api/snapshot/{version}      指定版本快照
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class LinkageController {

    private final LinkageService service;

    public LinkageController(LinkageService service) {
        this.service = service;
    }

    @PostMapping("/batches")
    public Object importBatch(@RequestBody ImportBatchRequest request) {
        return service.importBatch(request);
    }

    @PostMapping("/filter")
    public Object filter(@RequestBody FilterCriteria criteria) {
        return service.filter(criteria);
    }

    @PostMapping("/ld/jobs")
    public JobView submitLdJob(@RequestParam(required = false) Long snapshotVersion,
                               @RequestParam(required = false) Long timeoutMillis,
                               @RequestBody(required = false) List<String> variantIds) {
        return toView(service.submitLdJob(snapshotVersion, variantIds, timeoutMillis));
    }

    @GetMapping("/ld/jobs/{jobId}")
    public JobView jobStatus(@PathVariable String jobId) {
        return service.jobStatus(jobId)
                .map(LinkageController::toView)
                .orElseThrow(() -> new com.github.guoyyit.linkagedisequilibriumservicejava
                        .service.JobNotFoundException(jobId));
    }

    @PostMapping("/ld/jobs/{jobId}/cancel")
    public JobView cancelJob(@PathVariable String jobId) {
        service.cancelJob(jobId);
        return service.jobStatus(jobId).map(LinkageController::toView).orElseThrow();
    }

    @GetMapping("/ld/jobs/{jobId}/results")
    public Object jobResults(@PathVariable String jobId) {
        return service.jobResults(jobId).map(pairs -> (Object) new ResultEnvelope(pairs))
                .orElseGet(() -> new ResultEnvelope(List.of()));
    }

    @GetMapping("/snapshot")
    public Object currentSnapshot() {
        return service.currentSnapshot().orElseGet(() ->
                new com.github.guoyyit.linkagedisequilibriumservicejava
                        .dto.SnapshotView(0L, List.of(), 0, 0));
    }

    @GetMapping("/snapshot/{version}")
    public Object snapshot(@PathVariable long version) {
        return service.snapshot(version);
    }

    static JobView toView(LdJobState job) {
        return new JobView(job.jobId(), job.snapshotVersion(),
                job.status().name(), job.progressPercent(), job.error());
    }

    /** 结果信封：避免顶层裸数组。 */
    public record ResultEnvelope(List<?> pairs) {
    }
}
