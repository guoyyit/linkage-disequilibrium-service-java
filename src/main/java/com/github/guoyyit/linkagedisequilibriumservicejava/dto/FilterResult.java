package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

import java.util.List;

/**
 * 筛选结果。
 *
 * @param snapshotVersion 结果所属的已提交快照版本
 * @param retained        通过全部条件的位点标识（确定顺序）
 */
public record FilterResult(long snapshotVersion, List<String> retained) {
}
