package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 LD 计算。
 *
 * <p>对每一对位点，仅使用两个位点都有基因型（成对可用，pairwise complete）的样本，
 * 因此任一对的指标只依赖该对位点的数据，无论分批/分次/并发触发，结果都与同一数据全量重算一致。
 * <ul>
 *   <li>等位基因频率差：{@code |p1 - p2|}，p 为 alt 剂量均值/2，基于成对可用样本；</li>
 *   <li>r²：基因型剂量向量的 Pearson 相关系数平方。</li>
 * </ul>
 * 位点对顺序按标识排序后字典序生成，保证输出可复现。
 */
@Service
public class DefaultLdCalculator implements LdCalculator {

    private static final int MISSING = -1;
    private static final int PROGRESS_GRANULARITY = 256;

    @Override
    public LdPair pair(DatasetSnapshot snapshot, String variant1, String variant2) {
        int[] x = column(snapshot, variant1);
        int[] y = column(snapshot, variant2);
        return computePair(variant1, variant2, x, y);
    }

    @Override
    public List<LdPair> computeAll(DatasetSnapshot snapshot,
                                   List<String> requested,
                                   ProgressCallback progress,
                                   CancellationToken cancel) {
        // 去重 + 排序，得到确定顺序
        List<String> ids = new ArrayList<>(new java.util.TreeSet<>(requested));
        Map<String, Integer> index = new HashMap<>();
        for (String id : ids) {
            index.put(id, idIndex(snapshot, id));
        }
        int m = ids.size();
        long total = (long) m * (m - 1) / 2;
        List<LdPair> out = new ArrayList<>();
        if (total == 0) {
            if (progress != null) {
                progress.onProgress(100);
            }
            return out;
        }
        long done = 0;
        long nextCheckpoint = 0;
        List<String> samples = snapshot.sampleIds();
        for (int i = 0; i < m; i++) {
            int ix = index.get(ids.get(i));
            for (int j = i + 1; j < m; j++) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new ComputationCancelledException("LD 计算已取消");
                }
                int iy = index.get(ids.get(j));
                int[] x = new int[samples.size()];
                int[] y = new int[samples.size()];
                for (int k = 0; k < samples.size(); k++) {
                    Map<String, Integer> row = snapshot.matrix().get(samples.get(k));
                    x[k] = row == null ? MISSING : row.getOrDefault(ids.get(i), MISSING);
                    y[k] = row == null ? MISSING : row.getOrDefault(ids.get(j), MISSING);
                }
                out.add(computePair(ids.get(i), ids.get(j), x, y));
                done++;
                if (done >= nextCheckpoint || done == total) {
                    nextCheckpoint = done + Math.max(1, total / PROGRESS_GRANULARITY);
                    if (progress != null) {
                        progress.onProgress((int) Math.min(99, done * 100 / total));
                    }
                }
            }
        }
        if (progress != null) {
            progress.onProgress(100);
        }
        return out;
    }

    /** 核心数值：成对可用样本上的频率差与 r²。纯函数，无外部状态。 */
    static LdPair computePair(String v1, String v2, int[] x, int[] y) {
        int n = 0;
        long sx = 0;
        long sy = 0;
        for (int k = 0; k < x.length; k++) {
            if (x[k] != MISSING && y[k] != MISSING) {
                n++;
                sx += x[k];
                sy += y[k];
            }
        }
        if (n == 0) {
            return new LdPair(v1, v2, 0.0, 0.0, 0);
        }
        double meanX = (double) sx / n;
        double meanY = (double) sy / n;
        double sxx = 0.0;
        double syy = 0.0;
        double sxy = 0.0;
        for (int k = 0; k < x.length; k++) {
            if (x[k] != MISSING && y[k] != MISSING) {
                double dx = x[k] - meanX;
                double dy = y[k] - meanY;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
        }
        double p1 = meanX / 2.0;
        double p2 = meanY / 2.0;
        double r2;
        if (sxx <= 0.0 || syy <= 0.0) {
            r2 = 0.0; // 成对样本上位点退化为单态，相关系数无定义
        } else {
            r2 = (sxy * sxy) / (sxx * syy);
            if (r2 < 0.0) {
                r2 = 0.0;
            } else if (r2 > 1.0) {
                r2 = 1.0;
            }
        }
        return new LdPair(v1, v2, Math.abs(p1 - p2), r2, n);
    }

    private static int[] column(DatasetSnapshot snapshot, String variantId) {
        int idx = idIndex(snapshot, variantId);
        int[] col = new int[snapshot.sampleIds().size()];
        for (int k = 0; k < col.length; k++) {
            Map<String, Integer> row = snapshot.matrix().get(snapshot.sampleIds().get(k));
            col[k] = row == null ? MISSING : row.getOrDefault(variantId, MISSING);
        }
        return col;
    }

    private static int idIndex(DatasetSnapshot snapshot, String variantId) {
        List<Variant> variants = snapshot.variants();
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).id().equals(variantId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("快照 " + snapshot.version()
                + " 中不存在位点: " + variantId);
    }
}
