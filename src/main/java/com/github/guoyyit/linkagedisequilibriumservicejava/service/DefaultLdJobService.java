package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.LdPair;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.JobStatus;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.LdJobState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认 LD 任务管理。
 *
 * <ul>
 *   <li>去重：键为 {@code (快照版本, 规范化位点集合)}；并发或重复提交同一计算只执行一次，
 *       已在运行/已完成则复用同一任务。</li>
 *   <li>取消：协作式，计算线程在下一对位点前感知；取消/超时/异常只发布终态，results 始终为 null，
 *       不留下部分结果。</li>
 *   <li>快照固定：任务开始执行时按版本重新取出不可变快照，即使运行期间有新批次导入，
 *       结果仍属于提交时指定的已提交状态。</li>
 * </ul>
 */
@Service
public class DefaultLdJobService implements LdJobService {

    private final DatasetStore store;
    private final LdCalculator ldCalculator;

    private final Map<String, LdJobState> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> dedupKeyToJobId = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ld-compute");
        t.setDaemon(true);
        return t;
    });

    public DefaultLdJobService(DatasetStore store, LdCalculator ldCalculator) {
        this.store = store;
        this.ldCalculator = ldCalculator;
    }

    @Override
    public LdJobState submit(long snapshotVersion, List<String> variantIds, long timeoutMillis) {
        DatasetSnapshot snapshot = store.byVersion(snapshotVersion); // 快照必须存在
        List<String> normalized = List.copyOf(new TreeSet<>(variantIds));
        String dedupKey = snapshotVersion + "|" + String.join(",", normalized);
        String jobId = "ld-" + Integer.toHexString(dedupKey.hashCode());

        // computeIfAbsent 保证并发提交同一计算只创建/执行一次
        LdJobState job = jobs.computeIfAbsent(jobId, id -> {
            LdJobState created = new LdJobState(id, snapshotVersion, normalized, timeoutMillis);
            dedupKeyToJobId.put(dedupKey, id);
            start(created, snapshot);
            return created;
        });
        return job;
    }

    private void start(LdJobState job, DatasetSnapshot snapshot) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        Future<?> future = executor.submit(() -> run(job, snapshot, cancel));
        futures.put(job.jobId(), future);
        job.running();
    }

    private void run(LdJobState job, DatasetSnapshot snapshot, AtomicBoolean cancel) {
        long deadline = System.currentTimeMillis() + job.timeoutMillis();
        LdCalculator.CancellationToken token = cancel::get;
        try {
            List<LdPair> pairs = ldCalculator.computeAll(
                    snapshot,
                    job.variantIds(),
                    percent -> {
                        job.progress(percent);
                        if (System.currentTimeMillis() > deadline) {
                            cancel.set(true);
                        }
                    },
                    () -> cancel.get() || Thread.currentThread().isInterrupted());
            if (cancel.get()) {
                if (System.currentTimeMillis() > deadline) {
                    job.timeout();
                } else {
                    job.cancelled();
                }
            } else {
                job.completed(pairs); // 只有此刻才一次性发布完整结果
            }
        } catch (ComputationCancelledException e) {
            if (System.currentTimeMillis() > deadline) {
                job.timeout();
            } else {
                job.cancelled();
            }
        } catch (Exception e) {
            job.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            futures.remove(job.jobId());
        }
    }

    @Override
    public Optional<LdJobState> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public JobStatus cancel(String jobId) {
        LdJobState job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        if (job.isTerminal()) {
            return job.status();
        }
        synchronized (job) {
            if (!job.isTerminal()) {
                Future<?> future = futures.get(jobId);
                if (future != null) {
                    future.cancel(true);
                }
                job.cancelled();
            }
        }
        return job.status();
    }

    @Override
    public Optional<List<LdPair>> results(String jobId) {
        LdJobState job = jobs.get(jobId);
        if (job == null) {
            throw new JobNotFoundException(jobId);
        }
        return job.status() == JobStatus.COMPLETED
                ? Optional.ofNullable(job.results())
                : Optional.empty();
    }
}
