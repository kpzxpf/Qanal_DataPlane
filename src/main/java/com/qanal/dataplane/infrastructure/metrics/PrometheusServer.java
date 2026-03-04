package com.qanal.dataplane.infrastructure.metrics;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight HTTP server that exposes {@code /metrics} for Prometheus scraping.
 */
public class PrometheusServer {

    private static final Logger log = LoggerFactory.getLogger(PrometheusServer.class);

    private final PrometheusMeterRegistry registry;
    private HttpServer httpServer;

    public PrometheusServer() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public void start(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        httpServer.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        httpServer.start();
        log.info("Prometheus metrics available at http://0.0.0.0:{}/metrics", port);
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
    }
}
