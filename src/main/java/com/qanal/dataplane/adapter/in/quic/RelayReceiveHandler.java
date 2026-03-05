package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.application.port.out.StoragePort;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.TreeMap;

/**
 * Handles a relay push from an ingress DataPlane.
 *
 * <p>Relay-push protocol (type byte 0x52 already consumed by StreamTypeRouter):
 * <pre>
 *  Bytes  0-35  : transferId (UTF-8, 36 bytes)
 *  Bytes 36-43  : fileSize   (int64 big-endian)
 *  Bytes 44-107 : checksum   (UTF-8, 64 bytes, zero-padded)
 *  Bytes 108+   : file data
 * </pre>
 *
 * The file is stored via {@link StoragePort} so recipients can download it.
 */
public class RelayReceiveHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(RelayReceiveHandler.class);

    /** Header size after type byte: transferId(36) + fileSize(8) + checksum(64) = 108 bytes. */
    private static final int META_HEADER_SIZE = 108;

    private final StoragePort storage;

    // Per-stream state
    private byte[] metaHeaderBuf  = new byte[META_HEADER_SIZE];
    private int    metaHeaderRead = 0;
    private boolean metaHeaderDone = false;

    private String     transferId;
    private long       fileSize;
    private String     checksum;
    private long       receivedDataBytes;

    // Temp file written while data is streaming
    private Path            tempFile;
    private RandomAccessFile raf;
    private FileChannel      fileChannel;

    public RelayReceiveHandler(StoragePort storage) {
        super(false); // do not auto-release — we read ByteBuf contents
        this.storage = storage;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        while (msg.isReadable()) {
            if (!metaHeaderDone) {
                int remaining = META_HEADER_SIZE - metaHeaderRead;
                int toRead    = Math.min(remaining, msg.readableBytes());
                msg.readBytes(metaHeaderBuf, metaHeaderRead, toRead);
                metaHeaderRead += toRead;

                if (metaHeaderRead == META_HEADER_SIZE) {
                    parseMetaHeader();
                    metaHeaderDone = true;
                }
            } else {
                writeData(msg);
            }
        }
        msg.release();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!metaHeaderDone) return;
        closeFileChannel();

        if (receivedDataBytes >= fileSize) {
            storeFile();
        } else {
            log.warn("Relay stream closed prematurely for transfer {} ({}/{} bytes)",
                    transferId, receivedDataBytes, fileSize);
            cleanupTemp();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Relay receive error for transfer {}: {}", transferId, cause.getMessage());
        try { closeFileChannel(); } catch (IOException ignored) {}
        cleanupTemp();
        ctx.close();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void parseMetaHeader() throws IOException {
        // transferId: bytes 0-35
        transferId = new String(metaHeaderBuf, 0, 36, StandardCharsets.UTF_8).trim();

        // fileSize: bytes 36-43
        fileSize = 0;
        for (int i = 36; i < 44; i++) {
            fileSize = (fileSize << 8) | (metaHeaderBuf[i] & 0xFF);
        }

        // checksum: bytes 44-107 (zero-padded, trim nulls)
        checksum = new String(metaHeaderBuf, 44, 64, StandardCharsets.UTF_8).trim().replace("\0", "");

        // Create temp file
        tempFile    = Files.createTempFile("relay-" + transferId + "-", ".tmp");
        raf         = new RandomAccessFile(tempFile.toFile(), "rw");
        fileChannel = raf.getChannel();

        log.info("Relay receive started: transfer={}, fileSize={}, checksum={}", transferId, fileSize, checksum);
    }

    private void writeData(ByteBuf msg) throws IOException {
        int      readable = msg.readableBytes();
        ByteBuffer nio    = msg.nioBuffer();
        while (nio.hasRemaining()) {
            fileChannel.write(nio);
        }
        receivedDataBytes += readable;
    }

    private void closeFileChannel() throws IOException {
        if (fileChannel != null) { fileChannel.close(); fileChannel = null; }
        if (raf != null)         { raf.close();         raf         = null; }
    }

    private void storeFile() {
        try {
            // Wrap the single temp file as a one-entry map for storage assembly
            var singleChunk = new TreeMap<Integer, Path>();
            singleChunk.put(0, tempFile);
            storage.assemble(transferId, singleChunk);
            log.info("Relay stored for transfer {} ({} bytes) — ready for download", transferId, receivedDataBytes);
        } catch (IOException e) {
            log.error("Failed to store relay file for transfer {}: {}", transferId, e.getMessage(), e);
            cleanupTemp();
        }
    }

    private void cleanupTemp() {
        if (tempFile != null) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
        }
    }
}
