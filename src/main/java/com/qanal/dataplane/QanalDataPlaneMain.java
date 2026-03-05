package com.qanal.dataplane;

import com.qanal.dataplane.adapter.in.quic.DownloadServerBootstrap;
import com.qanal.dataplane.adapter.in.quic.QuicServerBootstrap;
import com.qanal.dataplane.adapter.out.grpc.ControlPlaneGrpcAdapter;
import com.qanal.dataplane.adapter.out.quic.RelayForwarder;
import com.qanal.dataplane.adapter.out.storage.LocalStorageAdapter;
import com.qanal.dataplane.application.service.TransferEngine;
import com.qanal.dataplane.infrastructure.config.DataPlaneConfig;
import com.qanal.dataplane.infrastructure.metrics.PrometheusServer;
import com.typesafe.config.ConfigFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Qanal Data Plane — Composition Root.
 *
 * <p>Wired under new package structure.
 */
public class QanalDataPlaneMain {

    private static final Logger log = LoggerFactory.getLogger(QanalDataPlaneMain.class);

    public static void main(String[] args) throws Exception {
        // ── 1. Config ────────────────────────────────────────────────────────
        DataPlaneConfig cfg = DataPlaneConfig.from(ConfigFactory.load().getConfig("qanal"));

        log.info("╔══════════════════════════════════════╗");
        log.info("║       Qanal Data Plane v0.2          ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("Agent:       {} / {}", cfg.agent().id(), cfg.agent().region());
        log.info("QUIC:        {}:{}", cfg.quic().host(), cfg.quic().port());
        log.info("Control:     {}:{}", cfg.controlPlane().host(), cfg.controlPlane().grpcPort());
        log.info("Storage:     {} (download port: {})", cfg.storage().outputDir(), cfg.storage().downloadPort());

        // ── 2. Metrics ───────────────────────────────────────────────────────
        var prometheusServer = new PrometheusServer();
        MeterRegistry metrics = prometheusServer.getRegistry();

        new ClassLoaderMetrics().bindTo(metrics);
        new JvmMemoryMetrics().bindTo(metrics);
        new JvmGcMetrics().bindTo(metrics);
        new JvmThreadMetrics().bindTo(metrics);
        new ProcessorMetrics().bindTo(metrics);

        if (cfg.metrics().enabled()) {
            prometheusServer.start(cfg.metrics().prometheusPort());
        }

        // ── 3. gRPC adapter ──────────────────────────────────────────────────
        var cpAdapter = new ControlPlaneGrpcAdapter(cfg.controlPlane(), cfg.agent());

        // ── 4. Storage + relay ───────────────────────────────────────────────
        var storage  = new LocalStorageAdapter(cfg.storage().outputDir());
        var relayFwd = new RelayForwarder(cfg);

        // ── 5. Transfer engine ───────────────────────────────────────────────
        var engine = new TransferEngine(cpAdapter, storage, relayFwd, cfg, metrics);

        // ── 6. QUIC servers ──────────────────────────────────────────────────
        var quicServer     = new QuicServerBootstrap(cfg, engine, storage);
        var downloadServer = new DownloadServerBootstrap(cfg, storage);
        quicServer.start();
        downloadServer.start();

        // ── 7. Agent registration ────────────────────────────────────────────
        cpAdapter.registerAgent(
                resolvePublicHost(cfg),
                cfg.quic().port(),
                10_000_000_000L,
                availableDisk()
        );

        // ── 8. Heartbeat scheduler ───────────────────────────────────────────
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> Thread.ofVirtual().name("heartbeat").unstarted(r));

        scheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(cpAdapter, engine),
                cfg.controlPlane().heartbeatIntervalSec(),
                cfg.controlPlane().heartbeatIntervalSec(),
                TimeUnit.SECONDS
        );

        // ── 9. Graceful shutdown ─────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("Shutting down Data Plane...");
            scheduler.shutdown();
            downloadServer.shutdown();
            quicServer.shutdown();
            cpAdapter.close();
            prometheusServer.stop();
            log.info("Data Plane stopped.");
        }));

        // ── 10. Block main thread ────────────────────────────────────────────
        log.info("Data Plane ready. Waiting for transfers...");
        quicServer.awaitTermination();
    }

    private static void sendHeartbeat(ControlPlaneGrpcAdapter adapter, TransferEngine engine) {
        try {
            Runtime rt  = Runtime.getRuntime();
            double  mem = 1.0 - ((double) rt.freeMemory() / rt.maxMemory());
            double  cpu = com.sun.management.OperatingSystemMXBean.class.cast(
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            ).getCpuLoad();

            adapter.sendHeartbeat(
                    engine.activeCount(),
                    cpu,
                    mem,
                    engine.totalBytesInFlight()
            );
        } catch (Exception e) {
            LoggerFactory.getLogger(QanalDataPlaneMain.class)
                    .warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    private static String resolvePublicHost(DataPlaneConfig cfg) {
        String h = cfg.quic().host();
        return "0.0.0.0".equals(h) || h.isEmpty() ? resolveLocalHostname() : h;
    }

    private static String resolveLocalHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static long availableDisk() {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .getFileStores().iterator().next()
                    .getUsableSpace();
        } catch (Exception e) {
            return 0L;
        }
    }
}
