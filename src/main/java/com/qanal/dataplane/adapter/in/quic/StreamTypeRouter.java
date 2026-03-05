package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.application.port.out.StoragePort;
import com.qanal.dataplane.application.service.TransferEngine;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.file.Path;
import java.util.List;

/**
 * Peeks at the first byte of a QUIC stream to route it to the correct handler:
 *
 * <ul>
 *   <li>{@code 0x52} ('R') → {@link RelayReceiveHandler} (relay push from ingress DataPlane)</li>
 *   <li>anything else     → {@link ChunkReceiver} (chunk upload from CLI)</li>
 * </ul>
 *
 * This handler removes itself from the pipeline after routing.
 */
public class StreamTypeRouter extends ByteToMessageDecoder {

    private static final byte TYPE_RELAY = 0x52; // 'R'

    private final TransferEngine  engine;
    private final StoragePort     storage;
    private final DataPlaneConfig cfg;
    private final Path            tempDir;

    public StreamTypeRouter(TransferEngine engine, StoragePort storage, DataPlaneConfig cfg, Path tempDir) {
        this.engine  = engine;
        this.storage = storage;
        this.cfg     = cfg;
        this.tempDir = tempDir;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) return;

        byte firstByte = in.getByte(in.readerIndex());

        ctx.pipeline().remove(this);

        if (firstByte == TYPE_RELAY) {
            in.skipBytes(1); // consume the type byte
            ctx.pipeline().addLast(new RelayReceiveHandler(storage));
        } else {
            ctx.pipeline().addLast(new ChunkReceiver(engine, cfg, tempDir));
        }

        // Re-fire remaining data through the newly-added handler
        if (in.isReadable()) {
            ctx.fireChannelRead(in.retain());
        }
    }
}
