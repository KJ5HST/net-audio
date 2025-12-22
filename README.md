# net-audio

Generic TCP-based bidirectional audio streaming library for Java applications.

## Overview

net-audio provides infrastructure for streaming audio between applications over a network. Originally developed for ham radio remote control, it's suitable for any application requiring low-latency bidirectional audio transport.

**Features:**
- TCP server/client for bidirectional audio streaming
- Thread-safe ring buffer with jitter compensation
- Binary packet protocol with CRC32 validation
- Platform-aware virtual audio device detection
- Configurable audio format (default: 48kHz, 16-bit, mono)

## Installation

### Maven
```xml
<dependency>
    <groupId>com.yaesu</groupId>
    <artifactId>net-audio</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Building from Source
```bash
git clone https://github.com/KJ5HST/net-audio.git
cd net-audio
mvn clean install
```

Requires Java 17+ and Maven 3.6+.

## Usage

### Server (Audio Source)

```java
import com.yaesu.audio.*;

// Create server on port 4533
AudioStreamServer server = new AudioStreamServer(4533);

// Configure audio devices
AudioDeviceManager deviceManager = server.getDeviceManager();
List<AudioDeviceInfo> captureDevices = deviceManager.discoverCaptureDevices();
List<AudioDeviceInfo> playbackDevices = deviceManager.discoverPlaybackDevices();

// Find devices by name pattern
AudioDeviceInfo capture = deviceManager.findDeviceByPattern(captureDevices, "USB", "Audio");
AudioDeviceInfo playback = deviceManager.findDeviceByPattern(playbackDevices, "USB", "Audio");

server.setCaptureDevice(capture);
server.setPlaybackDevice(playback);

// Listen for events
server.addStreamListener(new AudioStreamListener() {
    @Override
    public void onClientConnected(String clientId, String address) {
        System.out.println("Client connected: " + address);
    }

    @Override
    public void onStatisticsUpdate(String clientId, AudioStreamStats stats) {
        System.out.printf("Latency: %dms, Buffer: %d%%%n",
            stats.getLatencyMs(), stats.getBufferFillPercent());
    }
    // ... implement other methods
});

// Start streaming
server.start();

// Later: stop
server.stop();
```

### Client (Audio Consumer)

```java
import com.yaesu.audio.*;
import com.yaesu.audio.client.*;

// Connect to server
AudioStreamClient client = new AudioStreamClient("192.168.1.100", 4533);

// Use virtual audio devices for bridging to other applications
AudioDeviceManager deviceManager = client.getDeviceManager();
VirtualAudioBridge bridge = new VirtualAudioBridge(deviceManager);

client.setCaptureDevice(bridge.findBestCaptureDevice());
client.setPlaybackDevice(bridge.findBestPlaybackDevice());

// Connect and stream
client.connect();

// Monitor connection
AudioStreamStats stats = client.getStats();
System.out.printf("RX: %.1f kB/s, TX: %.1f kB/s%n",
    stats.getRxKBps(), stats.getTxKBps());

// Disconnect
client.disconnect();
```

### Custom Audio Configuration

```java
AudioStreamConfig config = new AudioStreamConfig()
    .setSampleRate(48000)      // Hz
    .setBitsPerSample(16)      // bits
    .setChannels(1)            // mono
    .setFrameDurationMs(20)    // 20ms frames
    .setBufferTargetMs(100);   // 100ms target buffer

AudioStreamServer server = new AudioStreamServer(4533, config);
```

## Virtual Audio Setup

For bridging audio between applications, you'll need virtual audio devices:

### macOS
```bash
brew install blackhole-2ch
```
Select "BlackHole 2ch" in your application's audio settings.

### Windows
Download and install [VB-Cable](https://vb-audio.com/Cable/).
Use "CABLE Input" for capture, "CABLE Output" for playback.

### Linux
```bash
# PulseAudio
pactl load-module module-null-sink sink_name=virtual sink_properties=device.description=Virtual_Audio

# PipeWire (modern distros)
# Virtual devices often available by default
```

## Protocol

The protocol uses a binary packet format:

```
+--------+--------+--------+--------+-----------+---------+--------+
| Magic  | Ver    | Type   | SeqNum | PayloadLen| Payload | CRC32  |
| 0xAF01 | 1 byte | 1 byte | 4 byte | 2 bytes   | 0-8192  | 4 byte |
+--------+--------+--------+--------+-----------+---------+--------+
```

**Packet Types:**
| Type | Value | Description |
|------|-------|-------------|
| AUDIO_RX | 0x01 | Audio from server to client |
| AUDIO_TX | 0x02 | Audio from client to server |
| CONTROL | 0x03 | Handshake, config, commands |
| HEARTBEAT | 0x04 | Keep-alive |

Default port: **4533**

## Architecture

```
com.yaesu.audio
├── AudioStreamConfig       # Audio format configuration
├── AudioStreamStats        # Streaming statistics
├── AudioRingBuffer         # Thread-safe circular buffer
├── AudioDeviceInfo         # Device metadata
├── AudioDeviceManager      # Device discovery
├── AudioStreamListener     # Event observer interface
├── AudioStreamServer       # TCP server
├── client/
│   ├── AudioStreamClient   # TCP client
│   └── VirtualAudioBridge  # Virtual device helpers
└── protocol/
    ├── AudioPacket         # Packet serialization
    ├── ControlMessage      # Control protocol
    └── AudioProtocolHandler # Socket I/O
```

## License

LGPL v2.1 - See [LICENSE](LICENSE) for details.

## Author

Terrell Deppe (KJ5HST)
