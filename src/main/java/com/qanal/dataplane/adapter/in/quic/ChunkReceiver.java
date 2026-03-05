package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.adapter.out.hash.StreamingXxHasher;
import com.qanal.dataplane.application.service.TransferEngine;
import com.qanal.dataplane.domain.model.ChunkHeader;
import com.qanal.dataplane.domain.model.TransferState;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * B4 Fix: Handles one incoming QUIC stream = one chunk with 68-byte header.
 *
 * <p>Wire protocol (binary, big-endian):
 * <pre>
 *  Byte  0-35 : transferId  (UTF-8, 36 bytes, fixed UUID string)
 *  Byte 36-39 : chunkIndex  (int32)
 *  Byte 40-47 : offsetBytes (int64)
 *  Byte 48-55 : sizeBytes   (int64)
 *  Byte 56-63 : totalFileSize (int64)   ← NEW
 *  Byte 64-67 : totalChunks  (int32)    ← NEW
 *  Byte 68+   : chunk data
 * </pre>
 */
public class ChunkReceiver extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ChunkReceiver.class);

    private final TransferEngine  engine;
    private final DataPlaneConfig cfg;
    private final Path            tempDir;

    // Per-stream state (set after header parsed)
    private byte[]      headerBuf  = new byte[ChunkHeader.HEADER_SIZE];
    private int         headerRead = 0;
    private boolean     headerDone = false;

    private ChunkHeader header;
    private long        receivedBytes;

    private TransferState   state;
    private FileChannel     fileChannel;
    private RandomAccessFile raf;
    private Path            tempFile;

    private final StreamingXxHasher hasher    = new StreamingXxHasher();
    private final Instant           startTime = Instant.now();

    public ChunkReceiver(TransferEngine engine, DataPlaneConfig cfg, Path tempDir) {
        super(false);   // don't auto-release — we need ByteBuf contents
        this.engine  = engine;
        this.cfg     = cfg;
        this.tempDir = tempDir;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        while (msg.isReadable()) {
            if (!headerDone) {
                int remaining = ChunkHeader.HEADER_SIZE - headerRead;
                int toRead    = Math.min(remaining, msg.readableBytes());
                msg.readBytes(headerBuf, headerRead, toRead);
                headerRead += toRead;

                if (headerRead == ChunkHeader.HEADER_SIZE) {
                    parseHeader();
                    headerDone = true;
                }
            } else {
                writeChunkData(msg);
            }
        }
        msg.release();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!headerDone) return;
        if (receivedBytes >= header.sizeBytes()) {
            finalize(ctx);
        } else {
            log.warn("Stream closed prematurely for chunk {}/{} ({}/{} bytes)",
                    header.chunkIndex(), header.transferId(), receivedBytes, header.sizeBytes());
            cleanup();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception on chunk {}/{}: {}",
                header != null ? header.chunkIndex() : -1,
                header != null ? header.transferId() : "?",
                cause.getMessage());
        if (state != null) {
            engine.onChunkFailed(state, header.chunkIndex(), cause);
        }
        cleanup();
        ctx.close();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void parseHeader() throws IOException {
        header = ChunkHeader.parse(headerBuf);

        // B4 Fix: DataPlane now knows totalChunks and totalFileSize from header
        state = engine.getTransfer(header.transferId());
        if (state == null) {
            state = engine.registerTransfer(
                    header.transferId(),
                    header.totalFileSize(),
                    header.totalChunks()
            );
        }

        state.bytesInFlight.addAndGet(header.sizeBytes());

        tempFile    = tempDir.resolve(header.transferId() + "_" + header.chunkIndex() + ".tmp");
        raf         = new RandomAccessFile(tempFile.toFile(), "rw");
        fileChannel = raf.getChannel();

        log.debug("Receiving chunk {}/{}: offset={}, size={}",
                header.chunkIndex(), header.transferId(), header.offsetBytes(), header.sizeBytes());
    }

    private void writeChunkData(ByteBuf msg) throws IOException {
        int      readable  = msg.readableBytes();
        ByteBuffer nioBuffer = msg.nioBuffer();

        // xxHash64 on the fly (B2 fix: now uses proper StreamingXxHasher)
        hasher.update(nioBuffer.duplicate());

        // Zero-copy write to temp file
        while (nioBuffer.hasRemaining()) {
            fileChannel.write(nioBuffer);
        }

        receivedBytes += readable;
    }

    private void finalize(ChannelHandlerContext ctx) {
        try {
            if (fileChannel != null) fileChannel.close();
            if (raf != null) raf.close();

            String checksum   = hasher.hexDigest();
            long   duration   = Duration.between(startTime, Instant.now()).toMillis();
            double throughput = duration > 0
                    ? (receivedBytes * 8000.0) / duration   // bytes → bits/sec, single division
                    : 0.0;

            // BUG-1 Fix: register the file BEFORE calling onChunkCompleted.
            // If this is the last chunk, onChunkCompleted triggers finalizeTransfer →
            // computeOrderedHash synchronously on this thread. The file must be in the
            // map before that happens, otherwise the last chunk is missing from the hash.
            state.registerChunkFile(header.chunkIndex(), tempFile);

            engine.onChunkCompleted(state, header.chunkIndex(), checksum,
                    receivedBytes, throughput, duration);

        } catch (IOException e) {
            log.error("Error finalizing chunk {}/{}", header.chunkIndex(), header.transferId(), e);
            engine.onChunkFailed(state, header.chunkIndex(), e);
        }
        ctx.close();
    }

    private void cleanup() {
        try {
            if (fileChannel != null) fileChannel.close();
            if (raf != null) raf.close();
            if (tempFile != null) Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {}
    }
}
