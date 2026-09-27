package com.github.guoyyit.linkagedisequilibriumservicejava.service.ld;

import com.github.guoyyit.linkagedisequilibriumservicejava.config.TaskProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.NotFoundException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdComputeRequest;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdMatrix;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.TaskInfo;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.DataSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskHandle;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.task.TaskManager;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ensures LD matrices are computed at most once per committed state.
 *
 * <p>Deduplication key is {@code (snapshotVersion, windowBp)}: repeat and
 * concurrent requests with the same key join the same task, so the calculator
 * runs exactly once. A client supplied {@code taskId} for an existing key
 * becomes an alias of that task. Computation runs on the immutable snapshot
 * captured at submission, so concurrent imports cannot change the result
 * mid-flight.
 *
 * <p>Tasks live in memory; after a process exit a running computation is gone
 * (no partial matrix is ever stored), while committed batches remain
 * recoverable from the journal and the computation can simply be requested
 * again.
 */
@Service
public class DefaultLdService implements LdService {

    private final TaskManager taskManager;
    private final SnapshotStore store;
    private final LdCalculator calculator;
    private final TaskProperties taskProperties;

    /** key: snapshotVersion:windowBp -> canonical task id */
    private final Map<String, String> keyToTask = new ConcurrentHashMap<>();
    /** any task id (canonical or alias) -> canonical task id */
    private final Map<String, String> aliases = new ConcurrentHashMap<>();
    /** keys occupied by an explicit client id whose first attempt failed */
    private final Object startLock = new Object();

    public DefaultLdService(TaskManager taskManager, SnapshotStore store,
                            LdCalculator calculator, TaskProperties taskProperties) {
        this.taskManager = taskManager;
        this.store = store;
        this.calculator = calculator;
        this.taskProperties = taskProperties;
    }

    private static String key(long version, long windowBp) {
        return version + ":" + windowBp;
    }

    @Override
    public TaskInfo start(LdComputeRequest request) {
        long windowBp = Math.max(0, request.windowBp());
        long timeoutMillis = request.timeoutMillis() > 0
                ? request.timeoutMillis()
                : taskProperties.getDefaultTimeoutMillis();

        synchronized (startLock) {
            DataSnapshot snapshot = store.current();
            String k = key(snapshot.version(), windowBp);
            String canonical = keyToTask.get(k);
            if (canonical != null) {
                TaskHandle<LdMatrix> existing = taskManager.get(canonical);
                if (existing == null || existing.info().status().isTerminalFailure()) {
                    keyToTask.remove(k);
                } else {
                    if (request.taskId() != null && !request.taskId().isBlank()) {
                        registerAlias(request.taskId(), canonical);
                    }
                    return existing.info();
                }
            }

            String taskId = request.taskId() != null && !request.taskId().isBlank()
                    ? request.taskId()
                    : "ld-" + UUID.randomUUID();
            String existingAlias = aliases.get(taskId);
            if (existingAlias != null && !existingAlias.equals(taskId)) {
                TaskHandle<LdMatrix> handle = taskManager.get(existingAlias);
                if (handle != null && !handle.info().status().isTerminalFailure()) {
                    throw new ConflictException(
                            "task id already in use for a different computation: " + taskId);
                }
            } else if (taskManager.get(taskId) != null
                    && !taskManager.get(taskId).info().status().isTerminalFailure()) {
                throw new ConflictException(
                        "task id already in use for a different computation: " + taskId);
            }

            DataSnapshot captured = snapshot;
            TaskHandle<LdMatrix> handle = taskManager.submit(taskId,
                    ctx -> calculator.calculate(taskId, captured, windowBp, ctx),
                    timeoutMillis, captured.version());
            keyToTask.put(k, taskId);
            aliases.put(taskId, taskId);
            return handle.info();
        }
    }

    private void registerAlias(String alias, String canonical) {
        String prior = aliases.putIfAbsent(alias, canonical);
        if (prior != null && !prior.equals(canonical)) {
            throw new ConflictException(
                    "task id already in use for a different computation: " + alias);
        }
    }

    @Override
    public TaskInfo status(String taskId) {
        return resolveHandle(taskId).info();
    }

    @Override
    public boolean cancel(String taskId) {
        return resolveHandle(taskId).cancel();
    }

    @Override
    public LdMatrix result(String taskId) {
        TaskHandle<LdMatrix> handle = resolveHandle(taskId);
        try {
            LdMatrix matrix = handle.await();
            if (matrix == null) {
                throw new NotFoundException(
                        "task " + taskId + " has no result: " + handle.info().status());
            }
            return matrix;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting for task " + taskId, e);
        }
    }

    @Override
    public LdMatrix ensureMatrix(String preferredTaskId, long windowBp, long timeoutMillis) {
        TaskInfo info = start(new LdComputeRequest(preferredTaskId, windowBp, timeoutMillis));
        String canonical = preferredTaskId == null
                ? info.taskId()
                : aliases.getOrDefault(preferredTaskId, info.taskId());
        return result(canonical);
    }

    private TaskHandle<LdMatrix> resolveHandle(String taskId) {
        String canonical = aliases.getOrDefault(taskId, taskId);
        TaskHandle<LdMatrix> handle = taskManager.get(canonical);
        if (handle == null) {
            throw new NotFoundException("unknown task: " + taskId);
        }
        return handle;
    }
}
