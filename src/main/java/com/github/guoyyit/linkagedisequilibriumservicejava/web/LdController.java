package com.github.guoyyit.linkagedisequilibriumservicejava.web;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.FilterResponse;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdComputeRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdFilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.filter.VariantFilter;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.ld.LdService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** LD computation tasks, task control and deterministic variant filtering. */
@RestController
@RequestMapping("/api")
public class LdController {

    private final LdService ldService;
    private final VariantFilter variantFilter;

    public LdController(LdService ldService, VariantFilter variantFilter) {
        this.ldService = ldService;
        this.variantFilter = variantFilter;
    }

    /** Start or join an LD computation; same (data, window) joins the same task. */
    @PostMapping("/ld/computations")
    public TaskInfo start(@RequestBody(required = false) LdComputeRequest request) {
        return ldService.start(request == null ? LdComputeRequest.defaults() : request);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskInfo status(@PathVariable String taskId) {
        return ldService.status(taskId);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        boolean cancelled = ldService.cancel(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "cancelled", cancelled));
    }

    /** Blocks until the computation finishes and returns the LD matrix. */
    @GetMapping("/tasks/{taskId}/result")
    public LdMatrix result(@PathVariable String taskId) {
        return ldService.result(taskId);
    }

    /** Select variants by chromosome/window/MAF/missing/LD rules. */
    @PostMapping("/variants/filter")
    public FilterResponse filter(@RequestBody(required = false) LdFilterCriteria criteria) {
        return variantFilter.select(criteria == null ? LdFilterCriteria.none() : criteria);
    }
}
