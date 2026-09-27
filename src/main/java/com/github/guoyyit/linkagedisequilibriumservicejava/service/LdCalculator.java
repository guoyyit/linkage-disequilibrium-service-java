package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;

import java.util.List;

/** 连锁不平衡指标计算：等位基因频率差与基因型相关系数平方。 */
public interface LdCalculator {

    /** 计算快照中两个位点的 LD 指标。 */
    LdPair pair(DatasetSnapshot snapshot, String variant1, String variant2);

    /**
     * 计算快照中指定位点集合两两间的 LD。
     * 支持进度回调与取消；计算结果必须与全量重算一致（成对可用、缺失成对排除）。
     */
    List<LdPair> computeAll(DatasetSnapshot snapshot,
                            List<String> variantIds,
                            ProgressCallback progress,
                            CancellationToken cancel);

    /** 计算回调。 */
    interface ProgressCallback {
        void onProgress(int percent);
    }

    /** 协作式取消令牌。 */
    interface CancellationToken {
        boolean isCancelled();
    }
}
