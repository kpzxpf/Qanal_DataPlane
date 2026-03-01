package com.qanal.dataplane;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qanal Data Plane — QUIC-based file transfer engine.
 *
 * This is NOT a Spring application. It runs as a standalone Netty server
 * for maximum I/O performance with zero-copy transfers.
 *
 * Connects to Control Plane via gRPC to receive transfer commands
 * and report progress/completion.
 */
public class QanalDataPlaneMain {

    private static final Logger log = LoggerFactory.getLogger(QanalDataPlaneMain.class);

    public static void main(String[] args) {
        Config config = ConfigFactory.load().getConfig("qanal");

        log.info("=== Qanal Data Plane ===");
        log.info("Agent ID:    {}", config.getString("agent.id"));
        log.info("Region:      {}", config.getString("agent.region"));
        log.info("QUIC port:   {}", config.getInt("quic.port"));
        log.info("Control:     {}:{}",
                config.getString("control-plane.host"),
                config.getInt("control-plane.grpc-port"));

        // TODO: Phase 1 implementation
        // 1. Initialize gRPC client → Control Plane
        // 2. Register agent via RegisterAgent RPC
        // 3. Start QUIC server (Netty + netty-incubator-codec-quic)
        // 4. Start heartbeat scheduler
        // 5. Listen for incoming transfer connections

        log.info("Data Plane started. Waiting for transfers...");

        // Keep alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Data Plane...");
            // TODO: graceful shutdown — drain active transfers
        }));

        // Block main thread
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
