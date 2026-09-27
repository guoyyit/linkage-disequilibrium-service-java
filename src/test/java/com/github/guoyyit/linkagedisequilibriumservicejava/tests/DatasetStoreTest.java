package com.github.guoyyit.linkagedisequilibriumservicejava.tests;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.InMemoryDatasetStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.SnapshotNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetStoreTest {

    static Variant v(String id, long pos) {
        return new Variant(id, "1", pos, "A", "T");
    }

    static GenotypeBatch batch(String bid, List<String> samples, List<Variant> variants,
                               List<List<Integer>> g) {
        return new GenotypeBatch(bid, samples, variants, g);
    }

    @Test
    void incrementalBatchesVersionsAndImmutability(@TempDir Path dir) {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        GenotypeBatch b1 = batch("b1", List.of("s0", "s1"), List.of(v("A", 1), v("B", 2)),
                List.of(List.of(0, 1), List.of(2, 1)));
        DatasetSnapshot v1 = store.commit(b1);
        assertEquals(1L, v1.version());
        int samplesV1 = v1.sampleIds().size();
        int variantsV1 = v1.variants().size();

        GenotypeBatch b2 = batch("b2", List.of("s1", "s2"), List.of(v("B", 2), v("C", 3)),
                List.of(List.of(1, 0), List.of(2, 2)));
        DatasetSnapshot v2 = store.commit(b2);

        assertEquals(2L, v2.version());
        assertEquals(List.of("s0", "s1", "s2"), v2.sampleIds());
        assertEquals(List.of("A", "B", "C"),
                v2.variants().stream().map(Variant::id).toList());
        // 新样本 s2 在老位点 A 的值按缺失处理
        assertEquals(-1, v2.matrix().get("s2").get("A"));
        // 旧快照保持不可变
        assertEquals(samplesV1, v1.sampleIds().size());
        assertEquals(variantsV1, v1.variants().size());
        assertEquals(v1, store.byVersion(1));
        assertThrows(SnapshotNotFoundException.class, () -> store.byVersion(99));
    }

    @Test
    void duplicateCommitIsIdempotent(@TempDir Path dir) {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        GenotypeBatch b1 = batch("b1", List.of("s0"), List.of(v("A", 1)), List.of(List.of(0)));
        DatasetSnapshot v1 = store.commit(b1);
        // 重复提交同一批次（哪怕内容不同），不改变结果
        GenotypeBatch dup = batch("b1", List.of("s0"), List.of(v("A", 1)), List.of(List.of(2)));
        DatasetSnapshot again = store.commit(dup);
        assertEquals(1L, again.version());
        assertEquals(0, again.matrix().get("s0").get("A"));
        assertEquals(v1, again);
        assertTrue(store.containsBatch("b1"));
        assertFalse(store.containsBatch("nope"));
    }

    @Test
    void recoveryRebuildsFromPersistedFiles(@TempDir Path dir) {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        store.commit(batch("b1", List.of("s0", "s1"), List.of(v("A", 1)),
                List.of(List.of(0), List.of(2))));
        store.commit(batch("b2", List.of("s1", "s2"), List.of(v("B", 2)),
                List.of(List.of(0), List.of(2))));

        // 模拟进程重启：新实例从磁盘恢复
        InMemoryDatasetStore restarted = new InMemoryDatasetStore(dir.toString());
        restarted.recover();
        DatasetSnapshot snap = restarted.current();
        assertNotNull(snap);
        assertEquals(2L, snap.version());
        assertEquals(List.of("b1", "b2"), snap.batchIds());
        assertEquals(List.of("s0", "s1", "s2"), snap.sampleIds());
        assertEquals(List.of("A", "B"), snap.variants().stream().map(Variant::id).toList());
        // 恢复后继续提交，版本号延续
        DatasetSnapshot v3 = restarted.commit(batch("b3", List.of("s3"),
                List.of(v("C", 3)), List.of(List.of(1))));
        assertEquals(3L, v3.version());
        assertEquals(4, v3.sampleIds().size());
    }

    @Test
    void crashedTmpFilesAreDiscardedOnRecovery(@TempDir Path dir) throws Exception {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        store.commit(batch("b1", List.of("s0"), List.of(v("A", 1)), List.of(List.of(1))));
        // 模拟“写了临时文件但未 rename”的崩溃残片
        Files.writeString(dir.resolve("00000002-b2.json.tmp"), "{garbage}");
        assertTrue(Files.exists(dir.resolve("00000002-b2.json.tmp")));

        InMemoryDatasetStore restarted = new InMemoryDatasetStore(dir.toString());
        restarted.recover();
        assertEquals(1L, restarted.current().version());
        assertFalse(Files.exists(dir.resolve("00000002-b2.json.tmp")));
        // 恢复后提交产生的正式文件编号不与残片冲突
        DatasetSnapshot v2 = restarted.commit(batch("b2", List.of("s0"),
                List.of(v("B", 2)), List.of(List.of(1))));
        assertEquals(2L, v2.version());
        assertTrue(Files.exists(dir.resolve("00000002-b2.json")));
    }

    @Test
    void batchFilesArePersisted(@TempDir Path dir) throws Exception {
        InMemoryDatasetStore store = new InMemoryDatasetStore(dir.toString());
        store.commit(batch("b1", List.of("s0"), List.of(v("A", 1)), List.of(List.of(1))));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(f -> f.toString().endsWith(".json")).count());
        }
    }
}
