package com.github.guoyyit.linkagedisequilibriumservicejava.service.task;

/** Passed to task bodies for progress reporting and cooperative cancellation. */
public interface TaskContext {

    void progress(int percent);

    boolean isCancelled();

    /** Throws TaskCancelledException if cancellation was requested. */
    void checkpoint();
}
