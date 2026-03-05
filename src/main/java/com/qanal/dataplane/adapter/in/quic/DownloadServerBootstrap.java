package com.qanal.dataplane.adapter.in.quic;

import com.qanal.dataplane.application.port.out.StoragePort;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * QUIC server on the download port (default 4434) that serves assembled files to recipients.
 * Uses the same TLS context as the main QUIC server.
 */
public class DownloadServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DownloadServerBootstrap.class);

    private final DataPlaneConfig cfg;
    private final StoragePort     storage;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;

    public DownloadServerBootstrap(DataPlaneConfig cfg, StoragePort storage) {
        this.cfg     = cfg;
        this.storage = storage;
    }

    public void start() throws Exception {
        boolean useEpoll    = cfg.netty().useEpoll() && Epoll.isAvailable();
        int     workerCount = cfg.netty().workerThreads() > 0
                ? cfg.netty().workerThreads()
                : Runtime.getRuntime().availableProcessors() * 2;

        bossGroup   = useEpoll ? new EpollEventLoopGroup(1)           : new NioEventLoopGroup(1);
        workerGroup = useEpoll ? new EpollEventLoopGroup(workerCount) : new NioEventLoopGroup(workerCount);

        try {
            QuicSslContext sslCtx  = buildSslContext();
            ChannelHandler handler = buildQuicConfig(sslCtx);

            Class<? extends Channel> channelClass = useEpoll
                    ? EpollDatagramChannel.class : NioDatagramChannel.class;

            serverChannel = new Bootstrap()
                    .group(bossGroup)
                    .channel(channelClass)
                    .option(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)
                    .option(ChannelOption.SO_SNDBUF,  8 * 1024 * 1024)
                    .handler(handler)
                    .bind(new InetSocketAddress(cfg.quic().host(), cfg.storage().downloadPort()))
                    .sync().channel();

            log.info("Download QUIC server listening on {}:{} ({})",
                    cfg.quic().host(), cfg.storage().downloadPort(), useEpoll ? "epoll" : "NIO");
        } catch (Exception e) {
            workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            throw e;
        }
    }

    public void shutdown() {
        log.info("Shutting down download QUIC server...");
        if (serverChannel != null) serverChannel.close();
        if (workerGroup  != null) workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        if (bossGroup    != null) bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }

    private QuicSslContext buildSslContext() throws Exception {
        var quicCfg = cfg.quic();
        if (quicCfg.certPath() != null && quicCfg.keyPath() != null) {
            return QuicSslContextBuilder
                    .forServer(
                            java.nio.file.Paths.get(quicCfg.keyPath()).toFile(),
                            null,
                            java.nio.file.Paths.get(quicCfg.certPath()).toFile()
                    )
                    .applicationProtocols("qanal-download")
                    .build();
        }
        log.warn("Download server using self-signed certificate (DEV ONLY)");
        return QuicSslContextBuilder
                .forServer(SelfSignedCertificateHelper.privateKey(),
                           null,
                           SelfSignedCertificateHelper.certificate())
                .applicationProtocols("qanal-download")
                .build();
    }

    private ChannelHandler buildQuicConfig(QuicSslContext sslCtx) {
        final StoragePort sp = storage;
        return new QuicServerCodecBuilder()
                .sslContext(sslCtx)
                .maxIdleTimeout(cfg.quic().maxIdleTimeoutSec(), TimeUnit.SECONDS)
                .initialMaxData(cfg.quic().initialMaxData())
                .initialMaxStreamDataBidirectionalLocal(cfg.quic().initialMaxStreamData())
                .initialMaxStreamDataBidirectionalRemote(cfg.quic().initialMaxStreamData())
                .initialMaxStreamsBidirectional(8)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {}
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        stream.pipeline().addLast(new DownloadHandler(sp));
                    }
                })
                .build();
    }
}
