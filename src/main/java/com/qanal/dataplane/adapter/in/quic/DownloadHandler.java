package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.application.port.out.StoragePort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Handles recipient download requests on the download port (default 4434).
 *
 * <p>Download protocol:
 * <pre>
 *  Client → Server : transferId (UTF-8, 36 bytes)
 *  Server → Client : raw file bytes
 * </pre>
 *
 * The assembled file is streamed back and then deleted from local storage.
 */
public class DownloadHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(DownloadHandler.class);

    private static final int TRANSFER_ID_SIZE = 36;
    private static final int BUF_SIZE         = 8 * 1024 * 1024; // 8 MB

    private final StoragePort storage;

    // Per-stream request buffer
    private byte[] requestBuf  = new byte[TRANSFER_ID_SIZE];
    private int    requestRead = 0;
    private boolean requestDone = false;

    public DownloadHandler(StoragePort storage) {
        super(false); // manual release
        this.storage = storage;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        while (msg.isReadable() && !requestDone) {
            int remaining = TRANSFER_ID_SIZE - requestRead;
            int toRead    = Math.min(remaining, msg.readableBytes());
            msg.readBytes(requestBuf, requestRead, toRead);
            requestRead += toRead;

            if (requestRead == TRANSFER_ID_SIZE) {
                requestDone = true;
                String transferId = new String(requestBuf, StandardCharsets.UTF_8).trim();
                serveDownload(ctx, transferId);
            }
        }
        msg.release();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("DownloadHandler error: {}", cause.getMessage());
        ctx.close();
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void serveDownload(ChannelHandlerContext ctx, String transferId) {
        var fileOpt = storage.find(transferId);
        if (fileOpt.isEmpty()) {
            log.warn("Download requested for unknown transfer {}", transferId);
            ctx.close();
            return;
        }

        Path file = fileOpt.get();
        log.info("Serving download for transfer {} from {}", transferId, file);

        try {
            streamFile(ctx, file);
            // Half-close output to signal EOF
            ((QuicStreamChannel) ctx.channel()).shutdownOutput();
            log.info("Download complete for transfer {}", transferId);
        } catch (IOException e) {
            log.error("Error streaming file for transfer {}: {}", transferId, e.getMessage(), e);
            ctx.close();
        }
    }

    private void streamFile(ChannelHandlerContext ctx, Path file) throws IOException {
        var buf = ByteBuffer.allocateDirect(BUF_SIZE);
        try (var fc = FileChannel.open(file, StandardOpenOption.READ)) {
            buf.clear();
            int n;
            while ((n = fc.read(buf)) > 0) {
                buf.flip();
                byte[] arr = new byte[n];
                buf.get(arr);
                ctx.writeAndFlush(Unpooled.wrappedBuffer(arr));
                buf.clear();
            }
        }
    }
}
