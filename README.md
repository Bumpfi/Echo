# Echo Traffic Analyzer

A high-performance Android network traffic analyzer for scientific research.

## Overview

Echo is a local VPN-based network traffic analyzer that captures comprehensive network metadata without slowing down internet connectivity. It's designed for empirical research on mobile app network behavior.

## Features

- **Per-App Traffic Attribution**: Track bytes sent/received by each app using NetworkStatsManager
- **DNS Query Capture**: Full DNS query/response logging with timing data
- **TLS/SNI Extraction**: Identify encrypted traffic destinations via Server Name Indication
- **Zero-Overhead Design**: DNS-only VPN routing means regular traffic flows at full speed
- **Real-Time Monitoring**: Live dashboard showing app traffic statistics
- **JSON Export**: Export session data for analysis in Python/R/Excel

## How It Works

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                        ECHO APP                               │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │  DNS Capture   │  │  TLS/SNI       │  │  NetworkStats  │  │
│  │  & Forward     │  │  Extraction    │  │  Polling       │  │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘  │
│          └───────────────────┴───────────────────┘           │
│                              │                                │
│                    ┌─────────▼─────────┐                     │
│                    │ Traffic Repository │                     │
│                    │ (Data Aggregation) │                     │
│                    └───────────────────┘                     │
└──────────────────────────────────────────────────────────────┘
```

### VPN Routing Strategy

Echo uses **DNS-only routing** for optimal performance:

| Traffic Type | Routing | Purpose |
|--------------|---------|---------|
| DNS (port 53) | Through VPN | Capture domain queries |
| All other traffic | Direct | Full speed, no overhead |

This approach provides:
- ✅ Complete DNS query visibility
- ✅ Full internet speed
- ✅ Per-app traffic statistics via NetworkStatsManager
- ✅ TLS/SNI extraction from TCP handshakes

## Data Collected

### Per-App Metrics
- Bytes sent/received
- Packets sent/received
- First/last activity timestamps
- Connection count

### DNS Queries
- Domain name
- Query type (A, AAAA, CNAME, etc.)
- Response IP addresses
- Response time (latency)
- TTL value
- Response code

### TLS/Connection Info
- Destination IP/port
- SNI hostname
- TLS version (1.0, 1.1, 1.2, 1.3, QUIC)
- Protocol (TCP, UDP, QUIC)

## Installation

### From Source
```bash
git clone https://github.com/bumpfi/Echo.git
cd Echo
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Permissions Required
1. **VPN Permission**: Granted when starting recording
2. **Usage Stats Permission**: Settings → Apps → Special Access → Usage Access → Echo

## Usage

1. **Open Echo** and grant required permissions
2. **Tap Start Recording** to begin capturing traffic
3. **Use other apps** normally - their traffic will be monitored
4. **View per-app details** by tapping on an app in the list
5. **Stop recording** when done
6. **Export data** for analysis

## Technical Requirements

- **Android Version**: 10 (API 29) or higher
- **Permissions**: VPN, Usage Stats, Internet, Network State

## Project Structure

```
app/src/main/java/com/bumpfi/echo/
├── vpn/
│   ├── TrafficAnalyzerVpnService.kt  # Core VPN implementation
│   └── PacketParser.kt               # DNS/TLS packet parsing
├── data/
│   ├── TrafficRepository.kt          # Data aggregation
│   ├── AppInfoManager.kt             # App UID resolution
│   ├── SelectedAppsStore.kt          # App filter preferences
│   └── model/
│       └── TrafficData.kt            # Data classes
├── ui/
│   ├── screens/
│   │   ├── DashboardScreen.kt        # Main screen
│   │   ├── AppDetailScreen.kt        # Per-app details
│   │   └── SettingsScreen.kt         # Configuration
│   └── viewmodel/
│       └── TrafficViewModel.kt       # UI state
└── MainActivity.kt                    # Entry point
```

## Key Components

### TrafficAnalyzerVpnService
- Establishes VPN with DNS-only routing
- Forwards DNS queries to upstream server (8.8.8.8)
- Parses DNS responses for domain/IP mapping
- Polls NetworkStatsManager for per-app byte counts
- Attributes traffic to apps using UID mapping

### PacketParser
- Parses IPv4/IPv6 headers
- Extracts UDP/TCP port information
- Parses DNS query and response packets
- Extracts TLS Client Hello SNI extension
- Detects QUIC protocol

### TrafficRepository
- Aggregates traffic data per app
- Maintains connection and DNS query lists
- Calculates session statistics
- Provides JSON export functionality

## For Scientific Research

See [SCIENTIFIC_DATA_COLLECTION_GUIDE.md](SCIENTIFIC_DATA_COLLECTION_GUIDE.md) for:
- Detailed data schema documentation
- Step-by-step empirical study tutorial
- Python analysis code examples
- Statistical analysis guidance
- Limitations and validity considerations

## License

[Add your license here]

## Citation

If you use Echo for academic research, please cite:

```
Echo Traffic Analyzer: A Local VPN-Based Android Network Traffic
Analysis Tool for Privacy Research. [Year].
```

