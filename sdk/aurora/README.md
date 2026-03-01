# Probe — AuroraOS SDK

AuroraOS (Аврора) SDK for the Probe inspector tool.

> **Status**: Placeholder — implementation contributions welcome.

## Overview

AuroraOS is a Russian mobile platform based on Sailfish OS. The Probe plugin
for AuroraOS would allow developers to inspect network traffic, preferences,
and layout from applications built with the Aurora SDK (C++/Qt).

## Planned Architecture

```
probe-aurora/
├── ProbeCore/           # C++ core (transport, plugin interface)
│   ├── Probe.h/.cpp
│   ├── ProbePlugin.h
│   └── WebSocketTransport.h/.cpp
└── ProbeNetwork/        # Qt network interceptor
    ├── NetworkPlugin.h/.cpp
    └── HttpTransaction.h
```

## Integration (Future)

```cpp
// main.cpp
#include <Probe/Probe.h>
#include <ProbeNetwork/NetworkPlugin.h>

auto network = std::make_shared<NetworkPlugin>();

Probe::install(
    Probe::Builder()
        .serverUrl("ws://localhost:8484")
        .plugin(network)
        .build()
);
```

## Platform Notes

- AuroraOS apps are built with Qt/QML + C++
- Network interception via `QNetworkAccessManager` subclass
- WebSocket transport via `QWebSocket`
- `adb`-equivalent: Aurora Device Manager (ADM)

## Contributing

See the main [README](../../README.md) for the overall Probe architecture.
