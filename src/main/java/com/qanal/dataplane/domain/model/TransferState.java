package com.qanal.dataplane.domain.model;

import io.grpc.stub.StreamObserver;
import com.qanal.control.proto.ProgressUpdate;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory state for a single active transfer.
 * All fields are thread-safe for concurrent chunk operations.
 */
public final class TransferState {

    public final String   transferId;
    public final long     fileSize;
    public final int      totalChunks;

    // Progress counters
    public final AtomicInteger completedChunks  = new AtomicInteger(0);
    public final AtomicLong    bytesTransferred = new AtomicLong(0);
    public final AtomicLong    bytesInFlight    = new AtomicLong(0);

    // Chunk completion tracking
    private final BitSet completedBitSet;

    // Per-chunk retry counters
    private final ConcurrentHashMap<Integer, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    // Temp file paths keyed by chunkIndex (used for ordered hash computation at finalization)
    private final ConcurrentHashMap<Integer, Path> chunkFiles = new ConcurrentHashMap<>();

    // gRPC progress stream (opened once, shared across all chunks)
    public volatile StreamObserver<ProgressUpdate> progressStream;

    // Throughput tracking
    public final AtomicLong lastThroughputBps = new AtomicLong(0);

    public volatile boolean cancelled = false;

    public TransferState(String transferId, long fileSize, int totalChunks) {
        this.transferId      = transferId;
        this.fileSize        = fileSize;
        this.totalChunks     = totalChunks;
        this.completedBitSet = new BitSet(totalChunks);
    }

    public boolean markChunkDone(int index) {
        synchronized (completedBitSet) {
            completedBitSet.set(index);
            return completedBitSet.cardinality() >= totalChunks;
        }
    }

    public int incrementRetry(int chunkIndex) {
        return retryCounts.computeIfAbsent(chunkIndex, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    public int getRetryCount(int chunkIndex) {
        var counter = retryCounts.get(chunkIndex);
        return counter == null ? 0 : counter.get();
    }

    public void registerChunkFile(int chunkIndex, Path tempFile) {
        chunkFiles.put(chunkIndex, tempFile);
    }

    public Map<Integer, Path> getChunkFiles() {
        return chunkFiles;
    }

    public int progressPercent() {
        if (totalChunks == 0) return 0;
        return (int) (100L * completedChunks.get() / totalChunks);
    }
}
