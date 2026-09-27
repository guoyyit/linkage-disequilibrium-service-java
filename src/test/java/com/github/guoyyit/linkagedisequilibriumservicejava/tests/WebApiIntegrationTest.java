package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class WebApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeAll
    void setUp(@TempDir File tmp,
              @Autowired com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties storageProperties,
              @Autowired com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore store) {
        ReflectionTestUtils.setField(storageProperties, "dir", tmp.toPath().resolve("batches").toString());
        store.recover();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void a_importBatchThenDuplicate() throws Exception {
        String body = """
                {
                  "batchId": "api-b1",
                  "samples": ["s1", "s2", "s3", "s4"],
                  "variants": [
                    {"chromosome":"chr1","position":10,"referenceAllele":"A","alternateAllele":"T"},
                    {"chromosome":"chr1","position":20,"referenceAllele":"A","alternateAllele":"T"}
                  ],
                  "genotypes": [[0,1,1,2],[0,1,1,2]],
                  "options": {"sampleMissingThreshold":1.0,"variantMissingThreshold":1.0}
                }
                """;
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.state").value("COMMITTED"));

        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true));

        mvc.perform(get("/api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.batchCount").value(1));
    }

    @Test
    void b_invalidBatchReturnsLocatableErrors() throws Exception {
        String body = """
                {
                  "batchId": "api-bad",
                  "samples": ["s1", "s2"],
                  "variants": [
                    {"chromosome":"chrX","position":10,"referenceAllele":"A","alternateAllele":"T"}
                  ],
                  "genotypes": [[5, -1]],
                  "options": {"allowedChromosomes":["chr1"],
                               "sampleMissingThreshold":0.0,
                               "variantMissingThreshold":1.0}
                }
                """;
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].batchId").value("api-bad"));
    }

    @Test
    void c_ldTaskLifecycleAndResult() throws Exception {
        String start = "{\"windowBp\":0}";
        String taskId = mvc.perform(post("/api/ld/computations")
                        .contentType(MediaType.APPLICATION_JSON).content(start))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"taskId\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/tasks/" + taskId + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value(1));

        mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progressPercent").value(100));
    }

    @Test
    void d_filterReturnsPrunedSet() throws Exception {
        String body = "{\"maxLdR2\":0.5}";
        mvc.perform(post("/api/variants/filter")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").value(1))
                .andExpect(jsonPath("$.variants.length()").value(1));
    }

    @Test
    void e_unknownTaskIs404() throws Exception {
        mvc.perform(get("/api/tasks/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
