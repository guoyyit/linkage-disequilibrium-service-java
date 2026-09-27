package com.github.guoyyit.linkagedisequilibriumservicejava.dto;

/** 任务状态视图（不携带结果体，结果走独立接口）。 */
public record JobView(String jobId,
                      long snapshotVersion,
                      String status,
                      int progressPercent,
                      String error) {
}
