package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Snapshot of a long running task.
 */
public record TaskInfo(String taskId, TaskStatus status, int progressPercent,
                       String message, long snapshotVersion, Long elapsedMillis) {
}
