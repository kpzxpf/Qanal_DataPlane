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

    /**
     * Result of {@link #markChunkDone(int)}.
     */
    public enum ChunkMarkResult { DUPLICATE, IN_PROGRESS, ALL_DONE }

    /**
     * Marks a chunk as done. Returns:
     * <ul>
     *   <li>{@code DUPLICATE}   — chunk was already marked; caller must ignore it.</li>
     *   <li>{@code IN_PROGRESS} — chunk newly marked; not all chunks done yet.</li>
     *   <li>{@code ALL_DONE}    — chunk newly marked and all chunks are now complete.</li>
     * </ul>
     *
     * <p>BUG-2 Fix: the duplicate-check prevents double-finalization when the same
     * chunk arrives twice (application-level retry / QUIC retransmission).
     * BUG-4 Fix: {@code completedChunks} is incremented inside the synchronized
     * block so it stays consistent with the BitSet count and never exceeds
     * {@code totalChunks}.
     */
    public ChunkMarkResult markChunkDone(int index) {
        synchronized (completedBitSet) {
            if (completedBitSet.get(index)) {
                return ChunkMarkResult.DUPLICATE;
            }
            completedBitSet.set(index);
            completedChunks.incrementAndGet();   // moved inside sync — consistent with BitSet
            return completedBitSet.cardinality() >= totalChunks
                    ? ChunkMarkResult.ALL_DONE
                    : ChunkMarkResult.IN_PROGRESS;
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
