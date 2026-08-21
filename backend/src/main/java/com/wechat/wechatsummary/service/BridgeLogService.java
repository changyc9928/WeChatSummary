package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.config.StorageConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Collects log lines streamed from the Windows {@code bridge.exe} sidecar via
 * POST /api/tools/bridge/logs (the bridge's {@code --log-webhook} flag). The
 * in-memory ring keeps the most recent lines for the frontend log viewer, and
 * every line is also appended to {@code bridge-logs.log} in the tools dir so
 * the operator (or the backend itself) can inspect a full run after the fact.
 */
@Service
@Slf4j
public class BridgeLogService {

    private static final int RING_CAPACITY = 4000;
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * One log line as sent by the bridge. {@code seq} is the bridge's own
     * per-process counter; the backend renumbers lines with its own monotonic
     * cursor so incremental polling (GET ?after=N) works across bridge runs.
     */
    public record BridgeLogLine(String ts, String level, String msg) {
    }

    /** Stored form: backend-assigned sequence + the original line. */
    public record StoredLine(long seq, String ts, String level, String msg) {
    }

    private final StorageConfig storageConfig;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<StoredLine> ring = new ArrayList<>();
    private long cursor;

    public BridgeLogService(StorageConfig storageConfig) {
        this.storageConfig = storageConfig;
    }

    /**
     * Appends lines to the ring and to the on-disk log. Returns the count
     * ingested.
     */
    public int ingest(List<BridgeLogLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        Path file = logFile();
        lock.lock();
        try {
            StringBuilder batch = new StringBuilder();
            for (BridgeLogLine l : lines) {
                cursor++;
                StoredLine stored = new StoredLine(cursor,
                    l.ts() == null || l.ts().isEmpty() ? LocalDateTime.now().format(TS_FORMAT) : l.ts(),
                    l.level() == null ? "info" : l.level(),
                    l.msg() == null ? "" : l.msg());
                ring.add(stored);
                batch.append(stored.ts()).append(" [").append(stored.level()).append("] ")
                    .append(stored.msg()).append(System.lineSeparator());
            }
            if (ring.size() > RING_CAPACITY) {
                ring.subList(0, ring.size() - RING_CAPACITY).clear();
            }
            if (file != null) {
                try {
                    Files.writeString(file, batch.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    log.warn("Cannot append bridge log file {}: {}", file, e.toString());
                }
            }
            return lines.size();
        } finally {
            lock.unlock();
        }
    }

    /** Returns stored lines with seq &gt; after (ascending) plus the next cursor. */
    public List<StoredLine> since(long after) {
        lock.lock();
        try {
            List<StoredLine> out = new ArrayList<>();
            for (StoredLine l : ring) {
                if (l.seq() > after) {
                    out.add(l);
                }
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    public long cursor() {
        lock.lock();
        try {
            return cursor;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return ring.size();
        } finally {
            lock.unlock();
        }
    }

    /** Clears the in-memory ring (the on-disk log is kept as a full archive). */
    public void clear() {
        lock.lock();
        try {
            ring.clear();
        } finally {
            lock.unlock();
        }
    }

    private Path logFile() {
        try {
            return storageConfig.getToolsDir().resolve("bridge-logs.log");
        } catch (Exception e) {
            return null;
        }
    }
}
