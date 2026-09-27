package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

import java.util.List;

/**
 * 统一错误响应。{@code errors} 中的每一项都可定位到具体批次/样本/位点。
 */
public record ErrorResponse(String error, String batchId, List<String> details) {
}
