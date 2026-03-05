package com.qanal.dataplane.application.service;

import com.qanal.control.proto.ProgressUpdate;
import com.qanal.dataplane.adapter.out.hash.StreamingXxHasher;
import com.qanal.dataplane.adapter.out.quic.RelayForwarder;
import com.qanal.dataplane.application.port.out.ControlPlanePort;
import com.qanal.dataplane.application.port.out.StoragePort;
import com.qanal.dataplane.domain.model.TransferState;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mediator — maps QUIC stream events to transfer state.
 *
 * <p>B1 Fix: computeOrderedHash now uses FileChannel with 8MB buffer (no OOM).
 * B6-related: thread-safe registerTransfer via putIfAbsent.
 */
public class TransferEngine {

    private static final Logger log = LoggerFactory.getLogger(TransferEngine.class);

    private final ConcurrentHashMap<String, TransferState> activeTransfers =
            new ConcurrentHashMap<>();

    private final ControlPlanePort  cpPort;
    private final StoragePort       storage;
    private final RelayForwarder    relayForwarder;
    private final DataPlaneConfig   cfg;
    private final MeterRegistry     metrics;

    public TransferEngine(ControlPlanePort cpPort,
                          StoragePort storage,
                          RelayForwarder relayForwarder,
                          DataPlaneConfig cfg,
                          MeterRegistry metrics) {
        this.cpPort         = cpPort;
        this.storage        = storage;
        this.relayForwarder = relayForwarder;
        this.cfg            = cfg;
        this.metrics        = metrics;

        Gauge.builder("qanal.transfers.active", activeTransfers, ConcurrentHashMap::size)
                .description("Number of active transfers")
                .register(metrics);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Thread-safe registration — putIfAbsent prevents duplicate state on
     * parallel first-chunk arrivals (B6-related fix).
     */
    public TransferState registerTransfer(String transferId, long fileSize, int totalChunks) {
        var newState = new TransferState(transferId, fileSize, totalChunks);
        newState.progressStream = cpPort.openProgressStream();

        var existing = activeTransfers.putIfAbsent(transferId, newState);
        if (existing != null) {
            // Another thread already registered — close duplicate stream and return existing
            try { newState.progressStream.onCompleted(); } catch (Exception ignored) {}
            return existing;
        }

        log.info("Transfer {} registered: fileSize={}, chunks={}", transferId, fileSize, totalChunks);
        return newState;
    }

    public TransferState getTransfer(String transferId) {
        return activeTransfers.get(transferId);
    }

    // ── Chunk events ─────────────────────────────────────────────────────────

    public void onChunkCompleted(TransferState state, int chunkIndex,
                                  String checksum, long bytes,
                                  double throughputBps, long durationMs) {
        // BUG-2 + BUG-4 Fix: markChunkDone now returns an enum.
        // DUPLICATE → skip entirely; IN_PROGRESS/ALL_DONE → process normally.
        // completedChunks is incremented inside markChunkDone's synchronized block.
        var result = state.markChunkDone(chunkIndex);
        if (result == TransferState.ChunkMarkResult.DUPLICATE) {
            log.debug("Duplicate chunk {}/{} for transfer {} — ignored",
                    chunkIndex, state.totalChunks, state.transferId);
            return;
        }

        state.bytesTransferred.addAndGet(bytes);
        state.bytesInFlight.addAndGet(-bytes);
        state.lastThroughputBps.set((long) throughputBps);

        cpPort.reportChunkCompleted(
                state.transferId, chunkIndex, checksum, bytes, throughputBps, durationMs);

        log.debug("Chunk {}/{} done for transfer {} — {}%",
                chunkIndex, state.totalChunks, state.transferId, state.progressPercent());

        if (result == TransferState.ChunkMarkResult.ALL_DONE) {
            finalizeTransfer(state);
        }
    }

    public void onChunkFailed(TransferState state, int chunkIndex, Throwable cause) {
        log.error("Chunk {}/{} permanently failed for transfer {}: {}",
                chunkIndex, state.totalChunks, state.transferId, cause.getMessage());

        cpPort.reportError(
                state.transferId, chunkIndex,
                "CHUNK_TRANSFER_FAILED", cause.getMessage(),
                false,
                resp -> log.info("Error action for {}/{}: {}", state.transferId, chunkIndex, resp.getAction())
        );
    }

    // ── Progress reporting ────────────────────────────────────────────────────

    public void sendProgressUpdate(TransferState state, int activeStreams, double packetLossRate) {
        if (state.progressStream == null || state.cancelled) return;

        try {
            state.progressStream.onNext(ProgressUpdate.newBuilder()
                    .setTransferId(state.transferId)
                    .setBytesTransferred(state.bytesTransferred.get())
                    .setCurrentThroughputBps(state.lastThroughputBps.get())
                    .setActiveStreams(activeStreams)
                    .setPacketLossRate(packetLossRate)
                    .setTimestampMs(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.debug("Progress stream error for {}: {}", state.transferId, e.getMessage());
        }
    }

    // ── Finalization ──────────────────────────────────────────────────────────

    private void finalizeTransfer(TransferState state) {
        // 1 — Compute ordered hash without deleting chunk files yet
        String finalChecksum;
        var    ordered = new TreeMap<>(state.getChunkFiles());
        try {
            finalChecksum = computeOrderedHash(ordered);
        } catch (IOException e) {
            log.error("Failed to compute final checksum for transfer {}", state.transferId, e);
            cpPort.reportError(state.transferId, -1, "HASH_FAILURE", e.getMessage(), false, resp -> {});
            activeTransfers.remove(state.transferId);
            return;
        }
        log.info("Transfer {} hash computed — finalChecksum={}", state.transferId, finalChecksum);

        if (state.progressStream != null) {
            try { state.progressStream.onCompleted(); } catch (Exception ignored) {}
        }

        // 2 — Assemble chunk files into a single output file
        java.nio.file.Path assembledFile;
        try {
            assembledFile = storage.assemble(state.transferId, ordered);
        } catch (IOException e) {
            log.error("Failed to assemble transfer {}", state.transferId, e);
            cpPort.reportError(state.transferId, -1, "ASSEMBLY_FAILURE", e.getMessage(), false, resp -> {});
            activeTransfers.remove(state.transferId);
            return;
        }

        // 3 — Report finalization to Control Plane (blocking, returns egress info)
        var result = cpPort.reportTransferFinalized(state.transferId, finalChecksum);
        activeTransfers.remove(state.transferId);

        if (!result.verified()) {
            log.error("Transfer {} checksum rejected by Control Plane — deleting assembled file", state.transferId);
            storage.delete(state.transferId);
            return;
        }

        // 4 — If egress relay is needed, forward assembled file and delete local copy
        if (result.egressHost() != null && !result.egressHost().isEmpty()) {
            try {
                relayForwarder.relay(state.transferId, finalChecksum, assembledFile,
                        result.egressHost(), result.egressPort());
                storage.delete(state.transferId);
                log.info("Transfer {} relayed to egress {}:{} and local copy deleted",
                        state.transferId, result.egressHost(), result.egressPort());
            } catch (Exception e) {
                log.error("Relay to egress failed for transfer {}: {}", state.transferId, e.getMessage(), e);
                // Keep local copy — do not delete on relay failure
            }
        } else {
            // No separate egress: this DataPlane IS the egress — keep file for download
            log.info("Transfer {} assembled locally at {} (egress == ingress, ready for download)",
                    state.transferId, assembledFile);
        }
    }

    /**
     * B1 Fix: Streaming FileChannel with 8 MB buffer — no OOM on large files.
     * B2 Fix: Uses StreamingXxHasher (correct standard xxHash64).
     * Chunk files are NOT deleted here; assembly deletes them after copying.
     */
    private String computeOrderedHash(java.util.SortedMap<Integer, java.nio.file.Path> ordered) throws IOException {
        var hasher = new StreamingXxHasher();
        var buf    = new byte[8 * 1024 * 1024]; // 8 MB reusable buffer

        for (var path : ordered.values()) {
            try (var fc = FileChannel.open(path, StandardOpenOption.READ)) {
                var bb = ByteBuffer.wrap(buf);
                int n;
                while ((n = fc.read(bb)) > 0) {
                    hasher.update(buf, 0, n);
                    bb.clear();
                }
            }
        }
        return hasher.hexDigest();
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public Collection<TransferState> allActiveTransfers() {
        return activeTransfers.values();
    }

    public int activeCount() {
        return activeTransfers.size();
    }

    public long totalBytesInFlight() {
        return activeTransfers.values().stream()
                .mapToLong(s -> s.bytesInFlight.get())
                .sum();
    }
}
