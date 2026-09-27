package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 内存快照 + 本地文件持久化的数据集存储。
 *
 * <p>语义：
 * <ul>
 *   <li>每个批次校验通过后，先写入 {@code data-dir} 下 {@code NNNNNNNN-batchId.json}，
 *       采用临时文件 + 原子 rename，rename 成功即批次的提交点；随后发布新的不可变快照。</li>
 *   <li>进程异常退出时：已 rename 的批次在启动 {@link #recover()} 时重建，
 *       未 rename 的 {@code .tmp} 残片会被清除，不会留下部分结果。</li>
 *   <li>所有提交在同一把锁内串行化；读路径只访问不可变快照与 {@code volatile} 引用，
 *       因此读不到任何部分批次/部分位点写入。</li>
 *   <li>相同 batchId 的提交幂等：直接返回当前快照，版本不变。</li>
 * </ul>
 */
@Component
public class InMemoryDatasetStore implements DatasetStore {

    private static final int MISSING = -1;

    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object commitLock = new Object();

    private volatile DatasetSnapshot current;
    private final List<DatasetSnapshot> history = new ArrayList<>();
    private long sequence;

    public InMemoryDatasetStore(@Value("${ld.data-dir:./data/batches}") String dataDir) {
        this.dataDir = Path.of(dataDir);
    }

    @Override
    public DatasetSnapshot current() {
        return current;
    }

    @Override
    public DatasetSnapshot byVersion(long version) {
        synchronized (commitLock) {
            for (DatasetSnapshot s : history) {
                if (s.version() == version) {
                    return s;
                }
            }
            throw new SnapshotNotFoundException(version);
        }
    }

    @Override
    public boolean containsBatch(String batchId) {
        synchronized (commitLock) {
            if (current == null) {
                return false;
            }
            return current.batchIds().contains(batchId);
        }
    }

    @Override
    public DatasetSnapshot commit(GenotypeBatch batch) {
        synchronized (commitLock) {
            if (current != null && current.batchIds().contains(batch.batchId())) {
                return current; // 幂等：重复提交不改变任何状态
            }
            persistBatch(batch);
            DatasetSnapshot next = merge(current, batch, history.size() + 1L);
            history.add(next);
            current = next; // 最后一步发布；此前读者只能看到旧快照
            return next;
        }
    }

    @Override
    public void recover() {
        synchronized (commitLock) {
            try {
                Files.createDirectories(dataDir);
                // 清理崩溃遗留的临时文件：它们对应的批次从未到达提交点
                try (Stream<Path> tmp = Files.list(dataDir)) {
                    tmp.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                            .forEach(this::deleteQuietly);
                }
                List<Path> files;
                try (Stream<Path> all = Files.list(dataDir)) {
                    files = all.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                }
                DatasetSnapshot snap = null;
                long ver = 0;
                for (Path f : files) {
                    GenotypeBatch batch = readBatch(f);
                    if (snap != null && snap.batchIds().contains(batch.batchId())) {
                        continue; // 防御性去重
                    }
                    ver++;
                    snap = merge(snap, batch, ver);
                    history.add(snap);
                }
                sequence = ver;
                current = snap;
            } catch (IOException e) {
                throw new UncheckedIOException("恢复已提交批次失败: " + dataDir, e);
            }
        }
    }

    @Override
    public List<DatasetSnapshot> versions() {
        synchronized (commitLock) {
            return List.copyOf(history);
        }
    }

    // ---- 内部 ----

    private void persistBatch(GenotypeBatch batch) {
        try {
            Files.createDirectories(dataDir);
            sequence++;
            String fileName = String.format("%08d-%s.json", sequence, sanitize(batch.batchId()));
            Path target = dataDir.resolve(fileName);
            Path tmp = dataDir.resolve(fileName + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), batch);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("批次持久化失败: " + batch.batchId(), e);
        }
    }

    private GenotypeBatch readBatch(Path f) {
        try {
            return mapper.readValue(f.toFile(), GenotypeBatch.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取批次文件失败: " + f, e);
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 残片清理失败不影响恢复
        }
    }

    /**
     * 将新批次合并到旧快照，生成版本号为 version 的新不可变快照。
     * 样本/位点采用确定排序，缺失单元格按 -1 处理；相同(样本,位点)以任一非缺失值为准
     * （矛盾值已在提交前由校验拒绝）。
     */
    private DatasetSnapshot merge(DatasetSnapshot prev, GenotypeBatch batch, long version) {
        TreeSet<String> samples = new TreeSet<>();
        TreeSet<Variant> variants = new TreeSet<>(variantOrder());
        if (prev != null) {
            samples.addAll(prev.sampleIds());
            variants.addAll(prev.variants());
        }
        samples.addAll(batch.sampleIds());
        variants.addAll(batch.variants());

        List<String> sampleList = List.copyOf(samples);
        List<Variant> variantList = List.copyOf(variants);

        Map<String, Map<String, Integer>> matrix = new HashMap<>();
        for (String s : sampleList) {
            matrix.put(s, new HashMap<>());
        }
        if (prev != null) {
            for (Map.Entry<String, Map<String, Integer>> e : prev.matrix().entrySet()) {
                matrix.get(e.getKey()).putAll(e.getValue());
            }
        }
        for (int i = 0; i < batch.sampleIds().size(); i++) {
            String s = batch.sampleIds().get(i);
            Map<String, Integer> row = matrix.get(s);
            for (int j = 0; j < batch.variants().size(); j++) {
                String vid = batch.variants().get(j).id();
                int g = batch.genotypes().get(i).get(j);
                Integer old = row.get(vid);
                if (old == null || old == MISSING) {
                    row.put(vid, g);
                }
                // old 非缺失时保留；g 与 old 矛盾已在 validateAgainst 拒绝
            }
        }
        // 冻结；矩阵为密集表示，跨批次未覆盖的 (样本,位点) 一律规范化为缺失 -1
        Map<String, Map<String, Integer>> frozen = new HashMap<>();
        for (String s : sampleList) {
            Map<String, Integer> src = matrix.get(s);
            Map<String, Integer> row = new HashMap<>();
            for (Variant v : variantList) {
                Integer g = src.get(v.id());
                row.put(v.id(), g == null ? MISSING : g);
            }
            frozen.put(s, Map.copyOf(row));
        }
        TreeSet<String> batchIds = new TreeSet<>();
        if (prev != null) {
            batchIds.addAll(prev.batchIds());
        }
        batchIds.add(batch.batchId());
        return new DatasetSnapshot(version, List.copyOf(batchIds), sampleList, variantList,
                Map.copyOf(frozen));
    }

    private static Comparator<Variant> variantOrder() {
        return Comparator
                .comparing((Variant v) -> DefaultBatchValidationService.canonicalChrom(v.chrom()))
                .thenComparingLong(Variant::position)
                .thenComparing(Variant::id);
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
