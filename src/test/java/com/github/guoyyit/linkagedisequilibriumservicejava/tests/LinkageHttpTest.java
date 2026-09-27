package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultBatchValidationService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdCalculator;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLdJobService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultLinkageService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.DefaultVariantFilterService;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.InMemoryDatasetStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.LinkageService;
import com.github.guoyyit.linkagedisequilibriumservicejava.web.GlobalExceptionHandler;
import com.github.guoyyit.linkagedisequilibriumservicejava.web.LinkageController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LinkageHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockMvc mvc;
    private InMemoryDatasetStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = new InMemoryDatasetStore(tmp.toString());
        DefaultBatchValidationService validation = new DefaultBatchValidationService();
        ReflectionTestUtils.setField(validation, "defaultMinMaf", 0.0);
        ReflectionTestUtils.setField(validation, "defaultMaxMissingRate", 0.25);
        DefaultLdCalculator calc = new DefaultLdCalculator();
        DefaultVariantFilterService filter = new DefaultVariantFilterService(store, calc);
        DefaultLdJobService jobs = new DefaultLdJobService(store, calc);
        DefaultLinkageService facade = new DefaultLinkageService(store, validation, filter, jobs);
        ReflectionTestUtils.setField(facade, "defaultJobTimeoutMillis", 600_000L);
        mvc = MockMvcBuilders.standaloneSetup(new LinkageController(facade))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String batchJson(String bid, String... ids) {
        List<Map<String, Object>> variants = new ArrayList<>();
        long pos = 100;
        for (String id : ids) {
            variants.add(Map.of("id", id, "chrom", "1", "position", pos++, "ref", "A", "alt", "T"));
        }
        List<List<Integer>> g = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Integer> row = new ArrayList<>();
            for (int j = 0; j < ids.length; j++) {
                // 所有位点列相同（完全连锁），列为 0/1/1/2，多态且无缺失
                row.add(List.of(0, 1, 1, 2).get(i));
            }
            g.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", bid);
        body.put("sampleIds", List.of("s0", "s1", "s2", "s3"));
        body.put("variants", variants);
        body.put("genotypes", g);
        body.put("filterOptions", Map.of("maxMissingRate", 1.0));
        return mapper.valueToTree(body).toString();
    }

    @Test
    void importThenDuplicateThenRejectInvalid() throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("h1", "A", "B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPresent").value(false))
                .andExpect(jsonPath("$.snapshotVersion").value(1));

        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson("h1", "A", "B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPresent").value(true))
                .andExpect(jsonPath("$.snapshotVersion").value(1));

        String bad = batchJson("hbad", "A").replace("\"genotypes\":[[0]", "\"genotypes\":[[7]");
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.batchId").value("hbad"))
                .andExpect(jsonPath("$.details[0]").value(
                        org.hamcrest.Matchers.containsString("非法基因型编码")));

        mvc.perform(get("/api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.variantCount").value(2));
    }

    @Test
    void filterCombinations() throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content(batchJson("h1", "A", "B"))).andExpect(status().isOk());

        mvc.perform(post("/api/filter").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chrom":"1","windowStart":100,"windowEnd":101}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value(1))
                .andExpect(jsonPath("$.retained[0]").value("A"));

        // A、B 完全连锁，r2 阈值 0.9 时只保留 A
        mvc.perform(post("/api/filter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxLdR2\":0.9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retained.length()").value(1));

        mvc.perform(post("/api/filter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minMaf\":0.9}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ldJobLifecycleAndResults() throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content(batchJson("h1", "A", "B"))).andExpect(status().isOk());

        String response = mvc.perform(post("/api/ld/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("[\"A\",\"B\"]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String jobId = mapper.readTree(response).get("jobId").asText();

        assertEqualsTerminal(jobId, "COMPLETED");

        mvc.perform(get("/api/ld/jobs/{id}/results", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairs.length()").value(1))
                .andExpect(jsonPath("$.pairs[0].rSquared").value(1.0));

        // 重复提交同一计算 -> 同一 jobId（只执行一次）
        mvc.perform(post("/api/ld/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("[\"B\",\"A\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId));
    }

    @Test
    void unknownJobAndSnapshotAre404() throws Exception {
        mvc.perform(get("/api/ld/jobs/nope"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/snapshot/77"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recoveryContinuesAfterRestart(@TempDir Path dir2) throws Exception {
        // setUp 已用 @TempDir 建了一个目录；这里通过 store 提交后用新 store 指向同一路径恢复
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content(batchJson("h1", "A", "B"))).andExpect(status().isOk());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content(batchJson("h2", "A", "B", "C"))).andExpect(status().isOk());

        Path dataDir = (Path) ReflectionTestUtils.getField(store, "dataDir");
        try (var s = Files.list(dataDir)) {
            org.junit.jupiter.api.Assertions.assertEquals(2,
                    s.filter(f -> f.toString().endsWith(".json")).count());
        }

        InMemoryDatasetStore restarted = new InMemoryDatasetStore(dataDir.toString());
        restarted.recover();
        org.junit.jupiter.api.Assertions.assertEquals(2L, restarted.current().version());
        org.junit.jupiter.api.Assertions.assertEquals(3, restarted.current().variants().size());
    }

    private void assertEqualsTerminal(String jobId, String expected) throws Exception {
        String status = "PENDING";
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/ld/jobs/{id}", jobId))
                    .andReturn().getResponse().getContentAsString();
            status = mapper.readTree(body).get("status").asText();
            if (List.of("COMPLETED", "CANCELLED", "FAILED", "TIMEOUT").contains(status)) {
                break;
            }
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected, status);
    }
}
