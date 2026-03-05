package com.qanal.dataplane.infrastructure.config;

import com.typesafe.config.Config;

/**
 * Strongly-typed wrapper over the HOCON config.
 * Instantiated once in {@code QanalDataPlaneMain} and passed to all components.
 */
public record DataPlaneConfig(
        AgentConfig        agent,
        QuicConfig         quic,
        TransferConfig     transfer,
        ControlPlaneConfig controlPlane,
        NettyConfig        netty,
        StorageConfig      storage,
        MetricsConfig      metrics
) {

    public record AgentConfig(String id, String region) {}

    public record QuicConfig(
            String host,
            int    port,
            String certPath,
            String keyPath,
            int    maxIdleTimeoutSec,
            int    initialMaxStreamsBidirectional,
            long   initialMaxData,
            long   initialMaxStreamData
    ) {}

    public record TransferConfig(
            int  parallelStreams,
            int  readBufferSize,
            int  writeBufferSize,
            int  maxRetryPerChunk,
            long retryDelayMs
    ) {}

    public record ControlPlaneConfig(
            String  host,
            int     grpcPort,
            boolean tlsEnabled,
            int     heartbeatIntervalSec,
            long    progressReportIntervalMs
    ) {}

    public record NettyConfig(
            int     bossThreads,
            int     workerThreads,
            boolean useEpoll
    ) {}

    public record StorageConfig(String outputDir, int downloadPort) {}

    public record MetricsConfig(boolean enabled, int prometheusPort) {}

    // ── Factory ─────────────────────────────────────────────────────────────

    public static DataPlaneConfig from(Config c) {
        // BUG-13 Fix: use hasPath() so that missing optional env vars (${?AGENT_ID},
        // ${?AGENT_REGION}) don't throw ConfigException.Missing at startup.
        // application.conf provides defaults, but this guard covers any future
        // config restructuring where the key might genuinely be absent.
        return new DataPlaneConfig(
                new AgentConfig(
                        c.hasPath("agent.id")     ? c.getString("agent.id")     : "local-dev",
                        c.hasPath("agent.region") ? c.getString("agent.region") : "local"
                ),
                new QuicConfig(
                        c.getString("quic.host"),
                        c.getInt("quic.port"),
                        c.hasPath("quic.cert-path") ? c.getString("quic.cert-path") : null,
                        c.hasPath("quic.key-path")  ? c.getString("quic.key-path")  : null,
                        c.getInt("quic.max-idle-timeout-sec"),
                        c.getInt("quic.initial-max-streams-bidirectional"),
                        c.getLong("quic.initial-max-data"),
                        c.getLong("quic.initial-max-stream-data")
                ),
                new TransferConfig(
                        c.getInt("transfer.parallel-streams"),
                        c.getInt("transfer.read-buffer-size"),
                        c.getInt("transfer.write-buffer-size"),
                        c.getInt("transfer.max-retry-per-chunk"),
                        c.getLong("transfer.retry-delay-ms")
                ),
                new ControlPlaneConfig(
                        c.getString("control-plane.host"),
                        c.getInt("control-plane.grpc-port"),
                        c.hasPath("control-plane.tls-enabled") && c.getBoolean("control-plane.tls-enabled"),
                        c.getInt("control-plane.heartbeat-interval-sec"),
                        c.getLong("control-plane.progress-report-interval-ms")
                ),
                new NettyConfig(
                        c.getInt("netty.boss-threads"),
                        c.getInt("netty.worker-threads"),
                        c.getBoolean("netty.use-epoll")
                ),
                new StorageConfig(
                        c.hasPath("storage.output-dir") ? c.getString("storage.output-dir") : "/tmp/qanal-storage",
                        c.hasPath("storage.download-port") ? c.getInt("storage.download-port") : 4434
                ),
                new MetricsConfig(
                        c.getBoolean("metrics.enabled"),
                        c.getInt("metrics.prometheus-port")
                )
        );
    }
}
