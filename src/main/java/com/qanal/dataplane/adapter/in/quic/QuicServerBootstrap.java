package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.application.port.out.StoragePort;
import com.qanal.dataplane.application.service.TransferEngine;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.*;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Builds and starts the QUIC (UDP) server.
 */
public class QuicServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(QuicServerBootstrap.class);

    private final DataPlaneConfig cfg;
    private final TransferEngine  engine;
    private final StoragePort     storage;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;
    private Path           tempDir;

    public QuicServerBootstrap(DataPlaneConfig cfg, TransferEngine engine, StoragePort storage) {
        this.cfg     = cfg;
        this.engine  = engine;
        this.storage = storage;
        this.tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "qanal-chunks");
    }

    public void start() throws Exception {
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);

        tempDir.toFile().mkdirs();
        cleanupStaleTempFiles();

        boolean useEpoll    = cfg.netty().useEpoll() && Epoll.isAvailable();
        int     workerThreads = cfg.netty().workerThreads() > 0
                ? cfg.netty().workerThreads()
                : Runtime.getRuntime().availableProcessors() * 2;

        bossGroup   = useEpoll ? new EpollEventLoopGroup(cfg.netty().bossThreads())
                               : new NioEventLoopGroup(cfg.netty().bossThreads());
        workerGroup = useEpoll ? new EpollEventLoopGroup(workerThreads)
                               : new NioEventLoopGroup(workerThreads);

        // BUG-12 Fix: if bind/SSL setup fails after the event loop groups are created,
        // shut them down so we don't leak threads on startup failure.
        try {
            QuicSslContext sslCtx  = buildSslContext();
            ChannelHandler handler = buildQuicConfig(sslCtx);

            Class<? extends Channel> channelClass = useEpoll
                    ? EpollDatagramChannel.class : NioDatagramChannel.class;

            var bootstrap = new Bootstrap()
                    .group(bossGroup)
                    .channel(channelClass)
                    .option(ChannelOption.SO_RCVBUF, 4 * 1024 * 1024)
                    .option(ChannelOption.SO_SNDBUF,  4 * 1024 * 1024)
                    .handler(handler);

            InetSocketAddress bindAddress =
                    new InetSocketAddress(cfg.quic().host(), cfg.quic().port());

            serverChannel = bootstrap.bind(bindAddress).sync().channel();
            log.info("QUIC server listening on {} ({})",
                    bindAddress, useEpoll ? "epoll" : "NIO");
        } catch (Exception e) {
            workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            throw e;
        }
    }

    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) serverChannel.closeFuture().sync();
    }

    public void shutdown() {
        log.info("Shutting down QUIC server...");
        if (serverChannel != null) serverChannel.close();
        if (workerGroup  != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        if (bossGroup    != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

    private void cleanupStaleTempFiles() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        try (var stream = Files.list(tempDir)) {
            stream.filter(p -> p.toString().endsWith(".tmp"))
                  .filter(p -> {
                      try {
                          FileTime modified = Files.getLastModifiedTime(p);
                          return modified.toInstant().isBefore(cutoff);
                      } catch (IOException e) { return false; }
                  })
                  .forEach(p -> {
                      try {
                          Files.deleteIfExists(p);
                          log.info("Deleted stale temp file: {}", p);
                      } catch (IOException e) {
                          log.warn("Failed to delete stale temp file {}: {}", p, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.warn("Error scanning temp dir: {}", e.getMessage());
        }
    }

    private QuicSslContext buildSslContext() throws Exception {
        var quicCfg = cfg.quic();
        if (quicCfg.certPath() != null && quicCfg.keyPath() != null) {
            return QuicSslContextBuilder
                    .forServer(
                            Paths.get(quicCfg.keyPath()).toFile(),
                            null,
                            Paths.get(quicCfg.certPath()).toFile()
                    )
                    .applicationProtocols("qanal")
                    .build();
        }
        // BUG-12 Fix: fail fast in production instead of silently using a self-signed cert.
        String env = System.getenv("QANAL_ENV");
        if ("production".equalsIgnoreCase(env) || "prod".equalsIgnoreCase(env)) {
            throw new IllegalStateException(
                    "Production environment requires TLS certificates. " +
                    "Set QUIC_CERT_PATH and QUIC_KEY_PATH environment variables.");
        }
        log.warn("No TLS cert configured — using self-signed certificate (DEV ONLY, NEVER use in production)");
        return QuicSslContextBuilder
                .forServer(SelfSignedCertificateHelper.privateKey(),
                           null,
                           SelfSignedCertificateHelper.certificate())
                .applicationProtocols("qanal")
                .build();
    }

    private ChannelHandler buildQuicConfig(QuicSslContext sslCtx) {
        var quicCfg = cfg.quic();
        final Path            td      = tempDir;
        final DataPlaneConfig config  = cfg;
        final TransferEngine  eng     = engine;
        final StoragePort     sp      = storage;

        return new QuicServerCodecBuilder()
                .sslContext(sslCtx)
                .maxIdleTimeout(quicCfg.maxIdleTimeoutSec(), TimeUnit.SECONDS)
                .initialMaxData(quicCfg.initialMaxData())
                .initialMaxStreamDataBidirectionalLocal(quicCfg.initialMaxStreamData())
                .initialMaxStreamDataBidirectionalRemote(quicCfg.initialMaxStreamData())
                .initialMaxStreamsBidirectional(quicCfg.initialMaxStreamsBidirectional())
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        ch.pipeline().addLast(new ConnectionLogger());
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        stream.pipeline().addLast(new StreamTypeRouter(eng, sp, config, td));
                    }
                })
                .build();
    }

    private static class ConnectionLogger extends ChannelInboundHandlerAdapter {
        private static final Logger clog = LoggerFactory.getLogger("quic.connections");

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            clog.debug("QUIC connection established: {}", ctx.channel().remoteAddress());
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            clog.debug("QUIC connection closed: {}", ctx.channel().remoteAddress());
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            clog.warn("QUIC connection error: {}", cause.getMessage());
            ctx.close();
        }
    }
}
