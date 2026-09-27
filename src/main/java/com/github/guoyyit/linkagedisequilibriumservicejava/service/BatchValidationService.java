package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.BatchFilterOptions;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;

/** 导入前的结构完整性与位点/样本质量校验。任何不满足条件的记录都必须以可定位错误拒绝。 */
public interface BatchValidationService {

    /**
     * 校验单个批次自身（结构、编码、重复、MAF、缺失率、位置窗口）。
     * 通过返回规范化后的批次；不通过抛出 {@link BatchRejectedException}。
     */
    GenotypeBatch validate(GenotypeBatch batch, BatchFilterOptions options);

    /**
     * 校验批次与已提交快照合并时的一致性：位点定义冲突、相同(样本,位点)记录值冲突。
     */
    void validateAgainst(GenotypeBatch batch, DatasetSnapshot existing);
}
