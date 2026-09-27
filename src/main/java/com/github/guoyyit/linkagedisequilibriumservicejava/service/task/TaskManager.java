package com.github.guoyyit.linkagedisequilibriumservicejava.service.task;

/** Runs, tracks, cancels and timeouts long running tasks. */
public interface TaskManager {

    default <T> TaskHandle<T> submit(String taskId, TaskJob<T> job, long timeoutMillis) {
        return submit(taskId, job, timeoutMillis, -1);
    }

    /**
     * Submit a task tagged with the data version it operates on.
     *
     * @param snapshotVersion opaque version label surfaced via task info (-1 = n/a)
     */
    <T> TaskHandle<T> submit(String taskId, TaskJob<T> job, long timeoutMillis,
                             long snapshotVersion);

    <T> TaskHandle<T> get(String taskId);

    /** Task body receiving a live {@link TaskContext}. */
    @FunctionalInterface
    interface TaskJob<T> {
        T run(TaskContext context) throws Exception;
    }
}
