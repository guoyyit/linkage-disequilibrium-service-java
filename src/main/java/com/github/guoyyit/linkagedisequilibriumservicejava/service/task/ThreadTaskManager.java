package com.github.guoyyit.linkagedisequilibriumservicejava.service.task;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.TaskCancelledException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.TaskTimeoutException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cached-thread task manager with cooperative cancellation and hard timeout.
 *
 * <p>A timeout interrupts the worker thread; if the task ignores interruption
 * it still transitions to TIMEOUT and its late result is discarded. Cancellation
 * is cooperative: task bodies call {@link TaskContext#checkpoint()}.
 */
@Component
public class ThreadTaskManager implements TaskManager {

    private static final Logger log = LoggerFactory.getLogger(ThreadTaskManager.class);

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "task-timeout");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, ManagedHandle<?>> handles = new ConcurrentHashMap<>();

    @Override
    public <T> TaskHandle<T> submit(String taskId, TaskJob<T> job, long timeoutMillis,
                                    long snapshotVersion) {
        // A completed task is an idempotent cache hit; an active task is shared;
        // a failed/cancelled/timed-out task may be replaced by a fresh submission.
        while (true) {
            @SuppressWarnings("unchecked")
            ManagedHandle<T> existing = (ManagedHandle<T>) handles.get(taskId);
            if (existing != null && !existing.info().status().isTerminalFailure()) {
                return existing;
            }
            ManagedHandle<T> handle = new ManagedHandle<>(taskId, timeoutMillis, snapshotVersion);
            if (existing == null) {
                if (handles.putIfAbsent(taskId, handle) == null) {
                    handle.start(job);
                    return handle;
                }
                continue;
            }
            if (handles.replace(taskId, existing, handle)) {
                handle.start(job);
                return handle;
            }
        }
    }

    @Override
    public <T> TaskHandle<T> get(String taskId) {
        @SuppressWarnings("unchecked")
        ManagedHandle<T> handle = (ManagedHandle<T>) handles.get(taskId);
        return handle;
    }

    private final class ManagedHandle<T> implements TaskHandle<T>, TaskContext {

        private final String id;
        private final long timeoutMillis;
        private final long createdAt = System.currentTimeMillis();
        private final long snapshotVersion;
        private final AtomicReference<TaskStatus> status = new AtomicReference<>(TaskStatus.PENDING);
        private final AtomicReference<Integer> percent = new AtomicReference<>(0);
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile Thread worker;
        private volatile String message = "";
        private volatile ScheduledFuture<?> timeout;

        ManagedHandle(String id, long timeoutMillis, long snapshotVersion) {
            this.id = id;
            this.timeoutMillis = timeoutMillis;
            this.snapshotVersion = snapshotVersion;
        }

        void start(TaskManager.TaskJob<T> job) {
            status.set(TaskStatus.RUNNING);
            worker = new Thread(() -> run(job), "ld-task-" + id);
            worker.setDaemon(true);
            if (timeoutMillis > 0) {
                timeout = scheduler.schedule(this::onTimeout, timeoutMillis, TimeUnit.MILLISECONDS);
            }
            worker.start();
        }

        private void run(TaskManager.TaskJob<T> job) {
            try {
                T value = job.run(this);
                if (status.compareAndSet(TaskStatus.RUNNING, TaskStatus.COMPLETED)) {
                    cancelTimeout();
                    percent.set(100);
                    message = "completed";
                    future.complete(value);
                }
            } catch (TaskCancelledException e) {
                finishAbnormal(TaskStatus.CANCELLED, e.getMessage(), e);
            } catch (TaskTimeoutException e) {
                finishAbnormal(TaskStatus.TIMEOUT, e.getMessage(), e);
            } catch (Throwable t) {
                if (status.get() == TaskStatus.RUNNING) {
                    finishAbnormal(TaskStatus.FAILED,
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
                }
            }
        }

        private void finishAbnormal(TaskStatus target, String msg, Throwable t) {
            if (status.compareAndSet(TaskStatus.RUNNING, target)
                    || status.compareAndSet(TaskStatus.PENDING, target)) {
                cancelTimeout();
                message = msg;
                log.warn("task {} ended {}: {}", id, target, msg);
                future.completeExceptionally(t);
            }
        }

        private void onTimeout() {
            if (status.compareAndSet(TaskStatus.RUNNING, TaskStatus.TIMEOUT)) {
                message = "exceeded timeout of " + timeoutMillis + " ms";
                log.warn("task {} timed out", id);
                future.completeExceptionally(new TaskTimeoutException(message));
                Thread w = worker;
                if (w != null) {
                    w.interrupt();
                }
            }
        }

        private void cancelTimeout() {
            ScheduledFuture<?> t = timeout;
            if (t != null) {
                t.cancel(false);
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public TaskInfo info() {
            return new TaskInfo(id, status.get(), percent.get(), message, snapshotVersion,
                    System.currentTimeMillis() - createdAt);
        }

        @Override
        public boolean cancel() {
            if (status.compareAndSet(TaskStatus.RUNNING, TaskStatus.CANCELLED)
                    || status.compareAndSet(TaskStatus.PENDING, TaskStatus.CANCELLED)) {
                cancelTimeout();
                message = "cancelled by client";
                future.completeExceptionally(new TaskCancelledException(message));
                Thread w = worker;
                if (w != null) {
                    w.interrupt();
                }
                return true;
            }
            return false;
        }

        @Override
        public T await() throws InterruptedException {
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            }
        }

        @Override
        public void progress(int value) {
            percent.set(Math.max(0, Math.min(100, value)));
        }

        @Override
        public boolean isCancelled() {
            TaskStatus s = status.get();
            return s == TaskStatus.CANCELLED || s == TaskStatus.TIMEOUT;
        }

        @Override
        public void checkpoint() {
            if (Thread.currentThread().isInterrupted()) {
                throw new TaskCancelledException("interrupted");
            }
            TaskStatus s = status.get();
            if (s == TaskStatus.CANCELLED) {
                throw new TaskCancelledException(message);
            }
            if (s == TaskStatus.TIMEOUT) {
                throw new TaskTimeoutException(message);
            }
        }
    }
}
