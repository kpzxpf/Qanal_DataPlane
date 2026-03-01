# Qanal Data Plane

QUIC-based high-speed file transfer engine. Standalone Netty application — no Spring.

## Requirements

- Java 21+
- TLS certificate + key (required for QUIC)
- Running Control Plane instance

## Quick Start

```bash
# 1. Generate self-signed cert for dev
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
  -keyout key.pem -out cert.pem -days 365 -nodes \
  -subj "/CN=localhost"

# 2. Run
AGENT_ID=agent-01 \
AGENT_REGION=us-west-2 \
QUIC_CERT_PATH=cert.pem \
QUIC_KEY_PATH=key.pem \
./gradlew run
```

## Ports

| Service     | Port |
|-------------|------|
| QUIC        | 4433 |
| Prometheus  | 9091 |

## Build

```bash
./gradlew build           # compile + test
./gradlew installDist     # production distribution
```
