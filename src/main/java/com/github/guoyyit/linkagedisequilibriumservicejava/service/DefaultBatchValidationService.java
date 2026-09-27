package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.BatchFilterOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.Variant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认批次校验：
 * <ul>
 *   <li>结构完整性：批次/样本/位点非空，矩阵行列匹配，基因型编码合法（0/1/2，null/-1 缺失）；</li>
 *   <li>位点过滤：位置窗口、最小次等位基因频率（MAF）、位点缺失率，并拒绝单态位点；</li>
 *   <li>样本完整性：样本缺失率不得超过阈值；</li>
 *   <li>跨批次一致性：位点定义不得冲突，相同(样本,位点)已存在的基因型不得矛盾。</li>
 * </ul>
 * 任何不满足项都收集为“批次/样本/位点”可定位错误并整体拒绝，绝不静默忽略。
 */
@Service
public class DefaultBatchValidationService implements BatchValidationService {

    public static final int MISSING = -1;

    @Value("${ld.import.default-min-maf:0.0}")
    private double defaultMinMaf;

    @Value("${ld.import.default-max-missing-rate:0.1}")
    private double defaultMaxMissingRate;

    @Override
    public GenotypeBatch validate(GenotypeBatch batch, BatchFilterOptions options) {
        double minMaf = options != null && options.minVariantMaf() != null
                ? options.minVariantMaf() : defaultMinMaf;
        double maxMissing = options != null && options.maxMissingRate() != null
                ? options.maxMissingRate() : defaultMaxMissingRate;
        Long minPos = options != null ? options.minPosition() : null;
        Long maxPos = options != null ? options.maxPosition() : null;

        List<String> errors = new ArrayList<>();
        String bid = batch == null ? "?" : batch.batchId();

        if (batch == null) {
            throw new BatchRejectedException("?", List.of("批次为空"));
        }
        if (bid == null || bid.isBlank()) {
            errors.add("批次缺少 batchId");
        }
        List<String> sampleIds = batch.sampleIds();
        List<Variant> rawVariants = batch.variants();
        List<List<Integer>> genotypes = batch.genotypes();

        if (sampleIds == null || sampleIds.isEmpty()) {
            errors.add(loc(bid, null, null) + "样本列表为空");
            sampleIds = List.of();
        }
        if (rawVariants == null || rawVariants.isEmpty()) {
            errors.add(loc(bid, null, null) + "位点列表为空");
            rawVariants = List.of();
        }
        if (minMaf < 0 || minMaf > 0.5) {
            errors.add(loc(bid, null, null) + "minVariantMaf 必须位于 [0,0.5]: " + minMaf);
        }
        if (maxMissing < 0 || maxMissing > 1) {
            errors.add(loc(bid, null, null) + "maxMissingRate 必须位于 [0,1]: " + maxMissing);
        }
        if (minPos != null && maxPos != null && minPos > maxPos) {
            errors.add(loc(bid, null, null) + "位置窗口非法: [" + minPos + "," + maxPos + "]");
        }

        // ---- 样本 ----
        List<String> normSamples = new ArrayList<>();
        Set<String> sampleSeen = new HashSet<>();
        for (int i = 0; i < sampleIds.size(); i++) {
            String s = sampleIds.get(i);
            if (s == null || s.isBlank()) {
                errors.add(loc(bid, i, null) + "样本标识为空");
                continue;
            }
            if (!sampleSeen.add(s)) {
                errors.add(loc(bid, i, null) + "样本在批次内重复: " + s);
                continue;
            }
            normSamples.add(s);
        }

        // ---- 位点 ----
        List<Variant> normVariants = new ArrayList<>();
        Set<String> variantSeen = new HashSet<>();
        for (int j = 0; j < rawVariants.size(); j++) {
            Variant v = rawVariants.get(j);
            if (v == null) {
                errors.add(loc(bid, null, null) + "位点列#" + j + " 为空");
                continue;
            }
            if (v.id() == null || v.id().isBlank()) {
                errors.add(loc(bid, null, null) + "位点列#" + j + " 标识为空");
                continue;
            }
            if (!variantSeen.add(v.id())) {
                errors.add(loc(bid, null, v) + "位点在批次内重复: " + v.id());
                continue;
            }
            if (v.chrom() == null || v.chrom().isBlank()) {
                errors.add(loc(bid, null, v) + "染色体为空");
            }
            if (v.position() <= 0) {
                errors.add(loc(bid, null, v) + "物理位置必须为正数: " + v.position());
            }
            if (v.ref() == null || v.ref().isBlank() || v.alt() == null || v.alt().isBlank()) {
                errors.add(loc(bid, null, v) + "ref/alt 等位基因为空");
            } else if (v.ref().equalsIgnoreCase(v.alt())) {
                errors.add(loc(bid, null, v) + "ref 与 alt 不得相同");
            }
            if (minPos != null && v.position() < minPos) {
                errors.add(loc(bid, null, v) + "位置 " + v.position() + " 低于窗口下界 " + minPos);
            }
            if (maxPos != null && v.position() > maxPos) {
                errors.add(loc(bid, null, v) + "位置 " + v.position() + " 高于窗口上界 " + maxPos);
            }
            normVariants.add(new Variant(v.id(), canonicalChrom(v.chrom()), v.position(),
                    v.ref() == null ? null : v.ref().toUpperCase(),
                    v.alt() == null ? null : v.alt().toUpperCase()));
        }

        // ---- 基因型矩阵结构与编码 ----
        int nS = normSamples.size();
        int nV = normVariants.size();
        List<List<Integer>> normGenotypes = new ArrayList<>();
        if (genotypes == null) {
            errors.add(loc(bid, null, null) + "基因型矩阵为空");
            genotypes = List.of();
        } else if (genotypes.size() != sampleIds.size()) {
            errors.add(loc(bid, null, null) + "基因型行数 " + genotypes.size()
                    + " 与样本数 " + sampleIds.size() + " 不一致");
        }
        for (int i = 0; i < genotypes.size() && i < nS; i++) {
            List<Integer> row = genotypes.get(i);
            List<Integer> normRow = new ArrayList<>();
            if (row == null) {
                errors.add(loc(bid, i, null) + "样本基因型行为空");
                for (int j = 0; j < nV; j++) {
                    normRow.add(MISSING);
                }
            } else {
                if (row.size() != nV) {
                    errors.add(loc(bid, i, null) + "样本行长度 " + row.size()
                            + " 与位点数 " + nV + " 不一致");
                }
                for (int j = 0; j < nV; j++) {
                    Integer g = j < row.size() ? row.get(j) : null;
                    if (g == null || g == MISSING) {
                        g = MISSING; // null 与 -1 均表示缺失
                    } else if (g < -1 || g > 2) {
                        errors.add(loc(bid, i, j < normVariants.size() ? normVariants.get(j) : null)
                                + "非法基因型编码 " + g + "（仅允许 0/1/2，null/-1 表示缺失）");
                        g = MISSING;
                    }
                    normRow.add(g);
                }
            }
            normGenotypes.add(normRow);
        }

        if (!errors.isEmpty()) {
            throw new BatchRejectedException(bid, errors);
        }

        // ---- 质量过滤：位点缺失率 / MAF；样本缺失率 ----
        for (int j = 0; j < nV; j++) {
            Variant v = normVariants.get(j);
            int called = 0;
            int missing = 0;
            int doseSum = 0;
            for (int i = 0; i < nS; i++) {
                int g = normGenotypes.get(i).get(j);
                if (g == MISSING) {
                    missing++;
                } else {
                    called++;
                    doseSum += g;
                }
            }
            double missRate = (double) missing / nS;
            if (missRate > maxMissing + 1e-12) {
                errors.add(loc(bid, null, v) + "位点缺失率 " + fmt(missRate)
                        + " 超过阈值 " + fmt(maxMissing));
                continue;
            }
            if (called == 0) {
                errors.add(loc(bid, null, v) + "位点全部基因型缺失，无法计算频率");
                continue;
            }
            double af = doseSum / (2.0 * called);
            double maf = Math.min(af, 1.0 - af);
            if (maf <= 0.0) {
                errors.add(loc(bid, null, v) + "位点为单态（MAF=0），无法计算连锁不平衡");
            } else if (maf + 1e-12 < minMaf) {
                errors.add(loc(bid, null, v) + "位点次等位基因频率 " + fmt(maf)
                        + " 低于阈值 " + fmt(minMaf));
            }
        }
        for (int i = 0; i < nS; i++) {
            int missing = 0;
            for (int j = 0; j < nV; j++) {
                if (normGenotypes.get(i).get(j) == MISSING) {
                    missing++;
                }
            }
            double missRate = (double) missing / nV;
            if (missRate > maxMissing + 1e-12) {
                errors.add(loc(bid, i, null) + "样本缺失率 " + fmt(missRate)
                        + " 超过阈值 " + fmt(maxMissing));
            }
        }

        if (!errors.isEmpty()) {
            throw new BatchRejectedException(bid, errors);
        }
        return new GenotypeBatch(bid, normSamples, normVariants, normGenotypes);
    }

    @Override
    public void validateAgainst(GenotypeBatch batch, DatasetSnapshot existing) {
        if (existing == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        Map<String, Variant> existingVariants = new java.util.HashMap<>();
        for (Variant v : existing.variants()) {
            existingVariants.put(v.id(), v);
        }
        // 位点定义一致性
        for (Variant v : batch.variants()) {
            Variant old = existingVariants.get(v.id());
            if (old != null && !old.equals(v)) {
                errors.add(loc(batch.batchId(), null, v)
                        + "位点定义与已提交批次冲突: 已有 " + old);
            }
        }
        // 相同(样本,位点)记录值不得矛盾（缺失 -> 有值视为增量补全，允许）
        for (int i = 0; i < batch.sampleIds().size(); i++) {
            String sample = batch.sampleIds().get(i);
            Map<String, Integer> oldRow = existing.matrix().get(sample);
            if (oldRow == null) {
                continue;
            }
            for (int j = 0; j < batch.variants().size(); j++) {
                int g = batch.genotypes().get(i).get(j);
                if (g == MISSING) {
                    continue;
                }
                Integer old = oldRow.get(batch.variants().get(j).id());
                if (old != null && old != MISSING && old != g) {
                    errors.add(loc(batch.batchId(), i, batch.variants().get(j))
                            + "基因型与已提交记录冲突: 新值=" + g + " 已有值=" + old);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BatchRejectedException(batch.batchId(), errors);
        }
    }

    private static String loc(String bid, Integer sampleIdx, Variant variant) {
        StringBuilder sb = new StringBuilder("批次[").append(bid).append("]");
        if (variant != null) {
            sb.append(" 位点[").append(variant.id())
                    .append(" ").append(variant.chrom()).append(":").append(variant.position())
                    .append("]");
        }
        if (sampleIdx != null) {
            sb.append(" 样本行#").append(sampleIdx);
        }
        return sb.append(" ").toString();
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }

    /** 染色体规范化：去掉 chr 前缀并大写，保证跨批次与筛选比较一致。 */
    public static String canonicalChrom(String chrom) {
        if (chrom == null) {
            return null;
        }
        String c = chrom.trim();
        if (c.toLowerCase(java.util.Locale.ROOT).startsWith("chr")) {
            c = c.substring(3);
        }
        return c.toUpperCase(java.util.Locale.ROOT);
    }
}
