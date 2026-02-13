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
    <version>1.2.1</version>
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

For bridging audio between applications, you'll need virtual audio devices configured for **48000 Hz**.

### Using VirtualAudioBridge

The `VirtualAudioBridge` class provides platform-aware virtual audio support:

```java
import com.yaesu.audio.client.VirtualAudioBridge;

VirtualAudioBridge bridge = new VirtualAudioBridge(deviceManager);

// Get platform-specific setup instructions
System.out.println(bridge.getInstallInstructions());

// Find best virtual devices
AudioDeviceInfo capture = bridge.findBestCaptureDevice();
AudioDeviceInfo playback = bridge.findBestPlaybackDevice();

// Verify configuration (checks sample rate, channels)
VirtualAudioBridge.VerificationResult result = bridge.verifyDeviceConfiguration(capture, true);
if (!result.isSuccess()) {
    System.out.println("Issues: " + result.getIssues());
    System.out.println("Suggestions: " + result.getSuggestions());
}

// Generate detailed diagnostic report
System.out.println(bridge.generateEnhancedDiagnosticReport());

// Linux only: auto-configure virtual audio
if (bridge.getPlatform() == VirtualAudioBridge.Platform.LINUX) {
    VirtualAudioBridge.ConfigurationResult configResult = bridge.autoConfigureLinux();
    if (configResult.isSuccess()) {
        System.out.println("Virtual audio configured: " + configResult.getSinkName());
    }
}
```

### Platform-Specific Installation

**macOS (BlackHole):**
```bash
brew install blackhole-2ch
```
Then configure in Audio MIDI Setup:
1. Open `/Applications/Utilities/Audio MIDI Setup.app`
2. Select "BlackHole 2ch"
3. Set Format to **48000 Hz**

**Windows (VB-Cable):**
1. Download from [vb-audio.com/Cable](https://vb-audio.com/Cable/)
2. Install as Administrator
3. Configure both devices to **48000 Hz, 16-bit** in Sound settings

**Linux (PulseAudio/PipeWire):**
```bash
# Create virtual sink with correct sample rate
pactl load-module module-null-sink \
    sink_name=ftx1_audio \
    sink_properties=device.description=FTX1_Virtual_Audio \
    rate=48000 channels=1 format=s16le

# Create loopback for bidirectional audio
pactl load-module module-loopback \
    source=ftx1_audio.monitor \
    sink=ftx1_audio \
    latency_msec=20
```

Or use `autoConfigureLinux()` to create automatically.

### Audio Optimization Profiles

```java
// For FT8/digital modes - minimize latency (40ms buffer)
AudioStreamConfig config = AudioStreamConfig.ft8Optimized();

// For SSB voice - stability over WiFi/Internet (120ms buffer)
AudioStreamConfig config = AudioStreamConfig.voiceOptimized();

// Balanced default (100ms buffer)
AudioStreamConfig config = new AudioStreamConfig();
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
├── AudioStreamConfig       # Audio format configuration (with optimization profiles)
├── AudioStreamStats        # Streaming statistics
├── AudioRingBuffer         # Thread-safe circular buffer with jitter compensation
├── AudioDeviceInfo         # Device metadata
├── AudioDeviceManager      # Device discovery and classification
├── AudioStreamListener     # Event observer interface
├── AudioStreamServer       # TCP server (multi-client support)
├── AudioBroadcaster        # RX audio broadcast (1 → many clients)
├── AudioMixer              # TX arbitration (many → 1 with priority)
├── client/
│   ├── AudioStreamClient   # TCP client with auto-reconnect
│   └── VirtualAudioBridge  # Virtual device detection, verification, and configuration
│       ├── VerificationResult   # Device configuration check results
│       └── ConfigurationResult  # Auto-configure results (Linux)
└── protocol/
    ├── AudioPacket         # Packet serialization
    ├── ControlMessage      # Control protocol (connect, TX arbitration, client info)
    └── AudioProtocolHandler # Socket I/O
```

### Multi-Client Support

The server supports multiple simultaneous clients (default: 4):
- **RX Audio**: Broadcast from single capture to all clients
- **TX Audio**: Priority-based arbitration (first-come-first-served, preemption)
- **Client Identification**: Callsign, name, location visible to server and other clients

## License

Proprietary - See [LICENSE](LICENSE) for details.

## Author

Terrell Deppe (KJ5HST)
