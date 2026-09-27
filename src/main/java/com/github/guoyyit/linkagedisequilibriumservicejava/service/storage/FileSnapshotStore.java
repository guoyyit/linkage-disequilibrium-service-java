package com.github.guoyyit.linkagedisequilibriumservicejava.service.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.linkagedisequilibriumservicejava.config.StorageProperties;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchState;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.BatchSummary;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.CommittedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Snapshot storage backed by an in-memory atomic reference and a directory of
 * per-batch JSON files.
 *
 * <p>Commit protocol: validate under a commit lock against the current
 * snapshot, durably write {@code <id>.json.tmp}, fsync, atomically rename to
 * {@code <id>.json}, then swap the snapshot reference. A crash leaves either no
 * file, a complete tmp file (ignored on recovery) or a complete final file; no
 * partial batch is ever visible.
 */
@Component
public class FileSnapshotStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(FileSnapshotStore.class);

    private final StorageProperties properties;
    private final ObjectMapper objectMapper;
    private final Object commitLock = new Object();
    private final AtomicReference<ImmutableSnapshot> snapshot =
            new AtomicReference<>(ImmutableSnapshot.empty());

    public FileSnapshotStore(StorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSnapshot current() {
        return snapshot.get();
    }

    @Override
    public void commit(CommittedBatch batch) {
        synchronized (commitLock) {
            ImmutableSnapshot current = snapshot.get();
            if (current.containsBatch(batch.batchId())) {
                throw new ConflictException("batch already committed: " + batch.batchId());
            }
            Path target = targetFile(batch.batchId());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                Files.createDirectories(target.getParent());
                byte[] payload = objectMapper.writeValueAsBytes(batch);
                try (OutputStream out = Files.newOutputStream(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    out.write(payload);
                    out.flush();
                    if (out instanceof java.io.FileOutputStream fos) {
                        fos.getFD().sync();
                    }
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try {
                    target.getParent().toFile().setLastModified(System.currentTimeMillis());
                } catch (SecurityException ignored) {
                    // directory fsync is best effort on most JVM platforms
                }
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new UncheckedIOException("failed to persist batch " + batch.batchId(), e);
            }
            // Validation against in-flight state happens before durability;
            // append is first-write-wins and cannot fail for a new batchId.
            snapshot.set(current.append(batch));
        }
    }

    @Override
    public List<BatchSummary> batches() {
        ImmutableSnapshot current = snapshot.get();
        List<BatchSummary> summaries = new ArrayList<>();
        for (String id : current.batchIds()) {
            CommittedBatch b = loadFile(targetFile(id));
            summaries.add(new BatchSummary(id, BatchState.COMMITTED,
                    b.variants().size(), b.samples().size(), b.committedAtMillis()));
        }
        return List.copyOf(summaries);
    }

    @Override
    public Optional<CommittedBatch> batch(String batchId) {
        Path target = targetFile(batchId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(loadFile(target));
    }

    @Override
    public void recover() {
        synchronized (commitLock) {
            Path dir = Path.of(properties.getDir());
            if (!Files.isDirectory(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot create storage dir " + dir, e);
                }
                snapshot.set(ImmutableSnapshot.empty());
                return;
            }
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list storage dir " + dir, e);
            }
            List<CommittedBatch> loaded = new ArrayList<>();
            for (Path f : files) {
                loaded.add(loadFile(f));
            }
            loaded.sort(Comparator.comparingLong(CommittedBatch::committedAtMillis)
                    .thenComparing(CommittedBatch::batchId));
            snapshot.set(ImmutableSnapshot.fromBatches(loaded));
            // Remove stale tmp files from interrupted writes.
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                        .forEach(this::deleteQuietly);
            } catch (IOException e) {
                log.warn("could not clean tmp files in {}", dir, e);
            }
            log.info("recovered {} committed batch(es), snapshot version {}",
                    loaded.size(), snapshot.get().version());
        }
    }

    private Path targetFile(String batchId) {
        if (!batchId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("batchId may only contain letters, digits, '.', '_' or '-'");
        }
        return Path.of(properties.getDir(), batchId + ".json");
    }

    private CommittedBatch loadFile(Path file) {
        try {
            byte[] payload = Files.readAllBytes(file);
            return objectMapper.readValue(payload, CommittedBatch.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read batch file " + file, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            log.debug("could not delete {}", path);
        }
    }
}
