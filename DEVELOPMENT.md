# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Net-Audio Streaming Library - Generic TCP-based bidirectional audio streaming. This module provides reusable audio infrastructure for capturing, streaming, and playing audio over a network connection.

Author: Terrell Deppe (KJ5HST) | License: Proprietary (Commercial)

## Build Commands

```bash
cd net-audio
mvn clean install              # Build and install to local repo
mvn test                       # Run unit tests
mvn javadoc:javadoc            # Generate Javadocs
```

**Requirements**: Java 17+, Maven 3.6+

## Architecture

### Package Structure

```
com.yaesu.audio
├── AudioStreamConfig      # Audio format configuration (48kHz, 16-bit, mono)
├── AudioStreamStats       # Streaming statistics (throughput, latency, errors)
├── AudioRingBuffer        # Thread-safe circular buffer with jitter compensation
├── AudioDeviceInfo        # Audio device metadata wrapper
├── AudioDeviceManager     # Device discovery and line management
├── AudioStreamListener    # Observer interface for stream events
├── AudioStreamServer      # TCP server for bidirectional audio (port 4533)
├── client/
│   ├── AudioStreamClient  # TCP client for connecting to audio server
│   └── VirtualAudioBridge # Platform-specific virtual audio device helpers
└── protocol/
    ├── AudioPacket        # Binary packet format with CRC
    ├── ControlMessage     # Control protocol messages
    └── AudioProtocolHandler # Socket I/O with packet framing
```

### No External Dependencies

This module is standalone. It uses only:
- `javax.sound.sampled.*` (Java Sound API)
- `java.net.*` (Socket networking)
- `java.nio.*` (ByteBuffer, ByteOrder)
- `java.util.zip.CRC32` (Checksum)
- `java.util.concurrent.*` (Threading)
- `org.slf4j` (Logging API)

## Usage Examples

### Server Mode

```java
// Create audio server
AudioStreamServer server = new AudioStreamServer(4533);

// Configure audio devices
AudioDeviceManager deviceManager = server.getDeviceManager();
List<AudioDeviceInfo> captureDevices = deviceManager.discoverCaptureDevices();
List<AudioDeviceInfo> playbackDevices = deviceManager.discoverPlaybackDevices();

// Find device by pattern (e.g., for specific hardware)
AudioDeviceInfo capture = deviceManager.findDeviceByPattern(captureDevices, "USB", "Audio");
AudioDeviceInfo playback = deviceManager.findDeviceByPattern(playbackDevices, "USB", "Audio");

server.setCaptureDevice(capture);
server.setPlaybackDevice(playback);

// Add listener for events
server.addStreamListener(new AudioStreamListener() {
    @Override
    public void onClientConnected(String clientId, String address) {
        System.out.println("Client connected: " + address);
    }
    // ... other callbacks
});

// Start server
server.start();
```

### Client Mode

```java
// Create client
AudioStreamClient client = new AudioStreamClient("192.168.1.100", 4533);

// Configure virtual audio devices
AudioDeviceManager deviceManager = client.getDeviceManager();
VirtualAudioBridge bridge = new VirtualAudioBridge(deviceManager);
client.setCaptureDevice(bridge.findBestCaptureDevice());
client.setPlaybackDevice(bridge.findBestPlaybackDevice());

// Connect and stream
client.connect();

// Monitor statistics
AudioStreamStats stats = client.getStats();
System.out.println("Latency: " + stats.getLatencyMs() + "ms");

// Disconnect
client.disconnect();
```

### Device Pattern Matching

```java
AudioDeviceManager mgr = new AudioDeviceManager();

// Find by hardware name patterns
AudioDeviceInfo device = mgr.findDeviceByPattern(
    mgr.discoverCaptureDevices(),
    "FTX-1", "Yaesu", "USB Audio"
);

// Find virtual devices
AudioDeviceInfo virtual = mgr.findVirtualCaptureDevice();
```

## Protocol Specification

### Packet Format

```
+--------+--------+--------+--------+-----------+---------+--------+
| Magic  | Ver    | Type   | SeqNum | PayloadLen| Payload | CRC32  |
| 0xAF01 | 1 byte | 1 byte | 4 byte | 2 bytes   | 0-8192  | 4 byte |
+--------+--------+--------+--------+-----------+---------+--------+
```

### Packet Types

| Type | Value | Description |
|------|-------|-------------|
| AUDIO_RX | 0x01 | RX audio from server to client |
| AUDIO_TX | 0x02 | TX audio from client to server |
| CONTROL | 0x03 | Control messages (handshake, config) |
| HEARTBEAT | 0x04 | Keep-alive ping/pong |

### Audio Format

Default configuration (configurable via AudioStreamConfig):
- Sample rate: 48,000 Hz
- Sample size: 16 bits
- Channels: 1 (mono)
- Encoding: PCM signed, little-endian
- Frame duration: 20ms (960 samples per frame)

## Virtual Audio Setup (Client Side)

**Virtual audio drivers are required on the client side** when using `AudioStreamClient` for remote audio streaming. The client receives audio via TCP and must play it to a virtual audio device so that applications like WSJT-X can access it.

### Why Virtual Audio is Required

```
Radio ↔ AudioStreamServer ↔ Network ↔ AudioStreamClient ↔ Virtual Audio Device ↔ WSJT-X
```

The client does NOT play directly to speakers. Virtual audio devices create a software bridge that allows:
- Digital mode software to read RX audio from the remote radio
- Digital mode software to send TX audio back to the remote radio

### macOS

**BlackHole 2ch** (Recommended, Free):
```bash
brew install blackhole-2ch
```
Or download from: https://existential.audio/blackhole/

**Loopback** (Commercial, more features):
- Download from: https://rogueamoeba.com/loopback/

After installation, the device appears as "BlackHole 2ch" in audio settings.

### Windows

**VB-Cable** (Free):
- Download from: https://vb-audio.com/Cable/
- Requires admin rights to install
- Creates two devices:
  - "CABLE Input" - use for capture (TX audio from WSJT-X)
  - "CABLE Output" - use for playback (RX audio to WSJT-X)

**VoiceMeeter** (Free, more features):
- Download from: https://vb-audio.com/Voicemeeter/
- Provides multiple virtual inputs/outputs

### Linux

**PulseAudio null sink** (Built-in, no installation):
```bash
# Create virtual sink
pactl load-module module-null-sink sink_name=virtual sink_properties=device.description="Virtual_Audio"

# Create loopback for monitoring
pactl load-module module-loopback source=virtual.monitor

# Make persistent (add to /etc/pulse/default.pa)
```

**PipeWire** (Modern distros):
- Often includes virtual device support by default
- Check with: `pw-cli list-objects | grep -i virtual`

**JACK** (Low latency):
```bash
sudo apt install jackd2 qjackctl
```

### Device Auto-Detection

The `VirtualAudioBridge` class automatically detects virtual devices by pattern matching:

| Platform | Patterns Detected |
|----------|-------------------|
| macOS | "blackhole", "soundflower", "loopback" |
| Windows | "vb-audio", "cable", "virtual", "voicemeeter" |
| Linux | "pulse", "pipewire", "jack", "null" |

```java
VirtualAudioBridge bridge = new VirtualAudioBridge(deviceManager);
AudioDeviceInfo capture = bridge.findBestCaptureDevice();   // For TX audio
AudioDeviceInfo playback = bridge.findBestPlaybackDevice(); // For RX audio
```

### Troubleshooting

If virtual audio is not detected:
1. Verify the driver is installed: check system audio settings
2. Run diagnostics: `bridge.generateDiagnosticReport()`
3. List all devices: `deviceManager.discoverCaptureDevices()`
4. Manually specify device by name pattern if auto-detection fails

## Testing

```bash
mvn test    # 73 unit tests
```

Tests cover:
- `AudioStreamConfigTest` - Configuration calculations
- `AudioRingBufferTest` - Thread safety, read/write operations
- `AudioPacketTest` - Serialization/deserialization, CRC
- `ControlMessageTest` - Message type handling

## Design Patterns

- **Observer Pattern**: `AudioStreamListener` for stream event notifications
- **Builder Pattern**: `AudioStreamConfig` uses fluent setters
- **Thread Safety**: `AudioRingBuffer` uses `ReentrantLock` with conditions
- **Resource Management**: Server and client implement graceful shutdown
