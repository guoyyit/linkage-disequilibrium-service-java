package com.github.guoyyit.linkagedisequilibriumservicejava.service.task;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;

/** Handle to a managed task. */
public interface TaskHandle<T> {

    String id();

    TaskInfo info();

    boolean cancel();

    /** Blocks until terminal state; returns null if the task did not complete. */
    T await() throws InterruptedException;
}
