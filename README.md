# Qanal Data Plane

The byte-moving engine of the Qanal platform. Accepts file chunks over QUIC, assembles them, verifies checksums, forwards to egress relays, and serves downloads — all without Spring Boot. Built on Netty + quic-codec, Java 21.

---

## Table of Contents

- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Running Locally](#running-locally)
- [Configuration Reference](#configuration-reference)
- [Wire Protocols](#wire-protocols)
  - [Upload Protocol (port 4433)](#upload-protocol-port-4433)
  - [Relay Push Protocol (port 4433)](#relay-push-protocol-port-4433)
  - [Download Protocol (port 4434)](#download-protocol-port-4434)
- [Transfer Lifecycle](#transfer-lifecycle)
- [Metrics](#metrics)
- [Running Tests](#running-tests)
- [Production Deployment](#production-deployment)
- [Multi-Region Setup](#multi-region-setup)
- [Design Decisions](#design-decisions)

---

## Architecture

```
                        +--------------------------------------------------+
                        |          Data Plane (one per region)             |
                        |                                                  |
  CLI sender  --QUIC--> |  :4433  StreamTypeRouter                        |
  Relay node  --QUIC--> |           +- 0x52 byte -> RelayReceiveHandler   |
                        |           +- other     -> ChunkReceiver         |
                        |                |                                |
                        |         TransferEngine                          |
                        |           +- assembles chunks                   |
                        |           +- StreamingXxHasher (xxHash64)       |
                        |           +- LocalStorageAdapter                |
                        |                |                                |
                        |  ControlPlaneGrpcAdapter --gRPC--> CP          |
                        |                |                                |
                        |  RelayForwarder --QUIC--> egress node          |
                        |                                                  |
  CLI receiver --QUIC-- |  :4434  DownloadServerBootstrap                  |
                        |                                                  |
                        |  :9091  PrometheusServer                         |
                        +--------------------------------------------------+
```

The codebase follows **Hexagonal Architecture** with no dependency on Spring:

```
src/main/java/com/qanal/dataplane/
├── domain/
│   ├── model/          # ChunkHeader (68-byte record), TransferState
│   └── service/        # StreamingHasher interface
├── application/
│   ├── port/out/       # ControlPlanePort (FinalizeResult), StoragePort
│   └── service/        # TransferEngine
├── adapter/
│   ├── in/quic/        # StreamTypeRouter, ChunkReceiver, RelayReceiveHandler,
│   │                   # DownloadHandler, QuicServerBootstrap, DownloadServerBootstrap,
│   │                   # SelfSignedCertificateHelper
│   ├── out/grpc/       # ControlPlaneGrpcAdapter
│   ├── out/hash/       # StreamingXxHasher (lz4-java)
│   ├── out/quic/       # RelayForwarder
│   └── out/storage/    # LocalStorageAdapter
└── infrastructure/
    ├── config/         # DataPlaneConfig (HOCON), StorageConfig
    └── metrics/        # PrometheusServer
```

**Composition Root** (`QanalDataPlaneMain`) wires everything manually — no IoC container.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 21+ |
| Gradle | 8+ (wrapper included) |
| Control Plane | Running and reachable on gRPC port 9090 |

In production the DataPlane registers itself with the Control Plane at startup. For local development it connects to `localhost:9090` by default.

---

## Running Locally

### 1. Start the Control Plane first

```bash
cd ../ControlPlane
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### 2. Run the Data Plane

```bash
cd DataPlane
./gradlew run
```

Startup log:

```
╔══════════════════════════════════════╗
║       Qanal Data Plane v0.2          ║
╚══════════════════════════════════════╝
Agent:       local-dev / local
QUIC:        0.0.0.0:4433
Control:     localhost:9090
Storage:     /tmp/qanal-storage (download port: 4434)
Data Plane ready. Waiting for transfers...
```

### 3. Verify

```bash
# Prometheus metrics endpoint
curl http://localhost:9091/metrics | head -20
```

The DataPlane sends a heartbeat to the Control Plane every 5 seconds. Check Control Plane logs for:

```
Received heartbeat from agent local-dev
```

---

## Configuration Reference

Configuration uses **HOCON** (`src/main/resources/application.conf`) via Typesafe Config. All values have safe defaults; override with environment variables in production. Missing optional env vars (`${?VAR}`) fall back to the defaults defined in `application.conf` — the app will never crash on a missing optional variable.

### Agent

| Variable | Config Key | Default | Description |
|----------|-----------|---------|-------------|
| `AGENT_ID` | `qanal.agent.id` | `local-dev` | Unique identifier for this node. Use a meaningful name per region, e.g. `node-eu-west-1` |
| `AGENT_REGION` | `qanal.agent.region` | `local` | Region label, e.g. `us-east-1`. Must match a region the Control Plane can route to |

### QUIC Server

| Variable | Config Key | Default | Description |
|----------|-----------|---------|-------------|
| `QUIC_PORT` | `qanal.quic.port` | `4433` | UDP port for upload and relay push |
| `QUIC_CERT_PATH` | `qanal.quic.cert-path` | _(auto-generated)_ | Path to TLS certificate PEM file |
| `QUIC_KEY_PATH` | `qanal.quic.key-path` | _(auto-generated)_ | Path to TLS private key PEM file |

QUIC connection tuning (edit `application.conf` directly):

| Config Key | Default | Description |
|-----------|---------|-------------|
| `quic.max-idle-timeout-sec` | `30` | Seconds before an idle connection is closed |
| `quic.initial-max-streams-bidirectional` | `64` | Max concurrent QUIC streams per connection |
| `quic.initial-max-data` | `104857600` | Per-connection flow control window (100 MB) |
| `quic.initial-max-stream-data` | `16777216` | Per-stream flow control window (16 MB) |

### Control Plane Connection

| Variable | Config Key | Default | Description |
|----------|-----------|---------|-------------|
| `CONTROL_PLANE_HOST` | `qanal.control-plane.host` | `localhost` | Control Plane hostname or IP |
| `CONTROL_PLANE_GRPC_PORT` | `qanal.control-plane.grpc-port` | `9090` | Control Plane gRPC port |
| — | `qanal.control-plane.tls-enabled` | `false` | Set `true` in production to enable TLS on the gRPC channel |
| — | `qanal.control-plane.heartbeat-interval-sec` | `5` | How often to send heartbeat to Control Plane |
| — | `qanal.control-plane.progress-report-interval-ms` | `500` | How often to push progress updates |

### Storage

| Variable | Config Key | Default | Description |
|----------|-----------|---------|-------------|
| `QANAL_STORAGE_DIR` | `qanal.storage.output-dir` | `/tmp/qanal-storage` | Directory where assembled files are stored. Mount a persistent, fast volume here in production |
| `QANAL_DOWNLOAD_PORT` | `qanal.storage.download-port` | `4434` | UDP port for recipient downloads (QUIC) |

### Transfer Engine

| Config Key | Default | Description |
|-----------|---------|-------------|
| `qanal.transfer.parallel-streams` | `64` | Max concurrent QUIC streams per transfer |
| `qanal.transfer.read-buffer-size` | `2097152` | 2 MB DirectByteBuffer allocated per stream |
| `qanal.transfer.write-buffer-size` | `2097152` | 2 MB write buffer |
| `qanal.transfer.max-retry-per-chunk` | `3` | Max retry attempts per failed chunk |
| `qanal.transfer.retry-delay-ms` | `1000` | Delay between retry attempts |

### Metrics

| Config Key | Default | Description |
|-----------|---------|-------------|
| `qanal.metrics.enabled` | `true` | Expose Prometheus `/metrics` endpoint |
| `qanal.metrics.prometheus-port` | `9091` | HTTP port for metrics scraping |

### Netty

| Config Key | Default | Description |
|-----------|---------|-------------|
| `qanal.netty.boss-threads` | `1` | Number of acceptor threads |
| `qanal.netty.worker-threads` | `0` | `0` = `availableProcessors × 2` |
| `qanal.netty.use-epoll` | `true` | Use Linux epoll transport; falls back to NIO automatically on non-Linux |

---

## Wire Protocols

All protocols use **big-endian** byte order. QUIC provides reliable, ordered, multiplexed streams over UDP — each stream is independent and not subject to head-of-line blocking from other streams.

### Upload Protocol (port 4433)

Each QUIC stream carries exactly **one chunk**. The stream begins with a fixed 68-byte header, followed immediately by the chunk payload bytes.

```
Offset  Len  Type    Field
──────  ───  ──────  ─────────────────────────────────────────────────────
     0   36  UTF-8   transferId    (UUID v7 string, e.g. "019500ab-...")
    36    4  int32   chunkIndex    (0-based)
    40    8  int64   offsetBytes   (byte offset of this chunk within the file)
    48    8  int64   sizeBytes     (byte size of this chunk)
    56    8  int64   totalFileSize (total file size in bytes)
    64    4  int32   totalChunks   (total number of chunks for this transfer)
    68    N  bytes   chunk data    (exactly sizeBytes bytes)
```

The receiving `ChunkReceiver`:
1. Reads the 68-byte header (`ChunkHeader.parse()`)
2. Writes chunk bytes to `<storageDir>/<transferId>/<chunkIndex>.chunk`
3. Computes xxHash64 of the chunk data
4. Reports chunk completion to the Control Plane via gRPC

### Relay Push Protocol (port 4433)

Used when the ingress DataPlane forwards a fully assembled file to an egress DataPlane in another region. Shares the same port as uploads. Discriminated from upload streams by the **first byte being `0x52`** ('R').

```
Offset  Len  Type    Field
──────  ───  ──────  ─────────────────────────────────────────────────────
     0    1  byte    0x52 — relay discriminator ('R')
     1   36  UTF-8   transferId
    37    8  int64   fileSize
    45   64  UTF-8   xxHash64 checksum hex string (zero-padded to 64 bytes)
   109    N  bytes   raw file data
```

`RelayReceiveHandler` writes the file directly to `LocalStorageAdapter` and reports finalization to the Control Plane via gRPC. Once complete, the egress node is ready to serve downloads to recipients.

### Download Protocol (port 4434)

The recipient CLI opens a QUIC connection to the egress node's download port. The protocol is minimal:

```
Direction        Content
──────────────   ──────────────────────────────────────────────
Client → Server  transferId (UTF-8, exactly 36 bytes)
Server → Client  raw file bytes (streamed until EOF / stream close)
```

`DownloadServerBootstrap` reads the assembled file from `LocalStorageAdapter` and streams it into the QUIC channel. The transfer must be in `COMPLETED` status before the download is available — this is enforced by the CLI before connecting.

---

## Transfer Lifecycle

```
Step 1  CLI sender calls POST /api/v1/transfers on Control Plane
        Control Plane responds: relayHost, relayQuicPort, chunks[]

Step 2  CLI opens N QUIC streams to DataPlane :4433
        Each stream sends one chunk: 68-byte header + chunk data

Step 3  ChunkReceiver writes each chunk to disk
        Reports each chunk completion to Control Plane via gRPC
        (atomic Redis INCR tracks how many chunks are done)

Step 4  When all chunks received → TransferEngine.assembleFile()
        Chunks concatenated in order using FileChannel (streaming, no heap OOM)
        Full-file xxHash64 computed via 8 MB streaming buffer

Step 5  TransferEngine reports: FinalizeTransfer(transferId, computedChecksum)
        Control Plane verifies checksum against sender-provided value
        If egressHost is set, Control Plane responds with egress node address

Step 6  If P2P relay needed → RelayForwarder pushes assembled file to egress node
        Egress RelayReceiveHandler stores the file
        Egress node reports COMPLETED to Control Plane
        Control Plane marks transfer COMPLETED, publishes SSE event

Step 7  Recipient runs: qanal receive <transferId>
        CLI calls GET /api/v1/transfers/{id} → gets egressRelayHost + egressDownloadPort
        CLI connects via QUIC to egress DataPlane :4434
        Receives raw file bytes
```

---

## Metrics

Prometheus metrics are available at `http://<host>:9091/metrics`.

### JVM metrics (auto-registered)

| Metric | Description |
|--------|-------------|
| `jvm_memory_used_bytes` | Heap and non-heap memory usage |
| `jvm_gc_pause_seconds` | GC pause duration histogram |
| `jvm_threads_live` | Live thread count |
| `process_cpu_usage` | CPU utilization (0.0–1.0) |
| `jvm_classes_loaded` | Currently loaded classes |

### Custom Qanal metrics

| Metric | Type | Description |
|--------|------|-------------|
| `qanal_transfers_active` | Gauge | Transfers currently being received |
| `qanal_bytes_in_flight` | Gauge | Total bytes being received right now |
| `qanal_chunks_received_total` | Counter | Chunks successfully received since startup |
| `qanal_chunks_failed_total` | Counter | Chunks that failed (bad checksum, I/O error) |
| `qanal_assembly_duration_seconds` | Histogram | Time to assemble a full file from chunks |

### Prometheus scrape config

```yaml
scrape_configs:
  - job_name: qanal-dataplane
    static_configs:
      - targets: ['<dataplane-host>:9091']
    scrape_interval: 15s
```

---

## Running Tests

```bash
# All tests
./gradlew test

# Specific test class
./gradlew test --tests "com.qanal.dataplane.*"

# With verbose output
./gradlew test --info
```

Tests run without any external dependencies — no Docker, no Control Plane required. Network-bound tests use local loopback QUIC connections.

---

## Production Deployment

### Docker (recommended)

The `Dockerfile` performs a two-stage build:

```
Stage 1 (builder): eclipse-temurin:21-jdk-alpine  →  ./gradlew shadowJar
Stage 2 (runtime): eclipse-temurin:21-jre-alpine   →  runs app.jar
```

Runtime JVM flags: `-XX:+UseZGC -XX:MaxRAMPercentage=75`

Memory limit in `docker-compose.prod.yml`: **2 GB**

Storage volume is mounted at `/data/qanal-storage`. Use a fast, persistent SSD-backed volume in production. Do not use `/tmp` — it is cleared on container restart.

### Deploy via repo root

```bash
# From the repo root
cp .env.example .env
# Fill in AGENT_ID, AGENT_REGION, and other values

docker compose -f docker-compose.prod.yml up -d data-plane
```

Exposed ports:

| Port | Protocol | Purpose |
|------|----------|---------|
| `4433` | UDP | Chunk uploads (CLI senders) + relay push (DataPlane to DataPlane) |
| `4434` | UDP | File downloads (CLI receivers) |
| `9091` | TCP | Prometheus metrics (restrict to monitoring subnet) |

### TLS certificates

In development, `SelfSignedCertificateHelper` generates a self-signed certificate at startup. The CLI accepts self-signed certs in dev mode.

In production, provide a real certificate:

```bash
# Environment variables
QUIC_CERT_PATH=/certs/server.crt
QUIC_KEY_PATH=/certs/server.key

# Mount the certs directory into the container:
# volumes:
#   - /etc/letsencrypt/live/relay.yourdomain.com:/certs:ro
```

### Firewall rules

**QUIC requires UDP to be fully open end-to-end.** NAT must not block or rewrite UDP flows.

| Port | Protocol | Direction | Purpose |
|------|----------|-----------|---------|
| `4433` | UDP | Inbound | From CLI senders and other DataPlane relay nodes |
| `4434` | UDP | Inbound | From CLI receivers |
| `9090` | TCP | Outbound | gRPC to Control Plane |
| `9091` | TCP | Inbound | Prometheus (restrict to monitoring host) |

---

## Multi-Region Setup

Run one DataPlane per region. Each node self-registers with the single shared Control Plane at startup. No manual registry entry is needed — the Control Plane's `LatencyRouteSelectorAdapter` discovers nodes via heartbeat.

### Adding a new region

```bash
# On the new server, configure env vars:
AGENT_ID=node-eu-west-2
AGENT_REGION=eu-west-2
CONTROL_PLANE_HOST=<control-plane-hostname>
CONTROL_PLANE_GRPC_PORT=9090
QUIC_PORT=4433
QANAL_STORAGE_DIR=/data/qanal-storage
QANAL_DOWNLOAD_PORT=4434

# Start the DataPlane:
docker compose -f docker-compose.node.yml up -d
```

The node registers automatically. The Control Plane will begin routing transfers to the new region.

### How relay forwarding works

When a transfer has different source and target regions:

1. **Ingress DataPlane** (sender's region) receives all chunks and assembles the file
2. **RelayForwarder** pushes the assembled file to the **egress DataPlane** (recipient's region) using the relay push protocol (109-byte header + raw bytes)
3. **Recipient** downloads the file from the egress node — never from the ingress node

The egress node address is provided by the Control Plane in the `FinalizeTransfer` gRPC response.

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| No Spring Boot | Eliminates ~400 MB heap overhead from Spring context; the DataPlane is a high-throughput I/O process, not a web framework user |
| Composition Root in `main()` | All dependencies wired explicitly — no reflection, no classpath scanning, fully deterministic startup order |
| HOCON config (`${?VAR}` syntax) | Optional env var substitution: missing variables silently use the default rather than crashing at startup |
| Netty + QUIC (UDP) | Per-stream reliability without TCP head-of-line blocking; connection multiplexing; 0-RTT reconnection |
| Single port 4433 for upload + relay | `StreamTypeRouter` peeks the first byte to discriminate: `0x52` = relay push, anything else = chunk upload. Avoids exposing a second upload port |
| `FileChannel` streaming assembly | Full file hash and assembly use an 8 MB buffer; never reads the whole file into heap — eliminates `OutOfMemoryError` on files up to 100 TB |
| `StreamingXXHash64` (lz4-java) | xxHash64 reaches ~20 GB/s on modern hardware; correct for arbitrarily large files via incremental feed |
| Virtual thread heartbeat | Single virtual thread parks between intervals; zero OS threads consumed while waiting |
| Graceful shutdown hook | Stops accepting new connections → drains in-flight transfers → closes gRPC channel → stops Prometheus server → exits cleanly |
