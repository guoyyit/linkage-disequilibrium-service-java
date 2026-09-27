package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterCriteria;
import com.github.guoyyit.linkagedisequilibriumservicejava.dto.FilterResult;

/**
 * 按染色体/位置窗口/MAF/缺失率/LD 强度组合筛选位点。
 * 结果必须可复现：相同快照 + 相同条件 => 相同结果，无漂移。
 */
public interface VariantFilterService {
    FilterResult filter(FilterCriteria criteria);
}
