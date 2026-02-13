# CLAUDE.md - net-audio

This file provides guidance to Claude Code (claude.ai/code) when working with the net-audio module.

## Module Overview

Generic TCP-based bidirectional audio streaming library for Java. Provides real-time audio transport with jitter compensation, multi-client support, and TX arbitration. Used by ftx1-web for remote radio operation.

**Key Characteristics:**
- No external dependencies (Java 17 standard library only)
- Thread-safe ring buffers with condition variables
- Binary packet protocol with CRC32 validation
- Multi-client broadcasting with priority-based TX arbitration
- Platform-aware virtual audio device detection

## Build Commands

```bash
mvn clean install          # Build with tests (44 tests)
mvn test                   # Run unit tests
```

## Architecture

### Package Structure (`com.yaesu.audio`)

```
com.yaesu.audio
├── AudioStreamServer.java      # TCP server (port 4533), multi-client
├── AudioStreamClient.java      # Client connects to remote server
├── AudioRingBuffer.java        # Thread-safe circular buffer
├── AudioDeviceManager.java     # Device discovery, virtual audio detection
├── AudioBroadcaster.java       # RX distribution to all clients
├── AudioMixer.java             # TX arbitration, priority-based
├── AudioStreamConfig.java      # Configuration builder with profiles
├── AudioStreamListener.java    # Event callback interface
│
├── client/
│   └── AudioStreamClient.java  # Remote server connection
│
└── protocol/
    ├── AudioPacket.java        # Binary packet format (19-byte header + payload + CRC)
    ├── ControlMessage.java     # Handshake, TX arbitration, client discovery
    └── AudioProtocolHandler.java # Serialization, heartbeat, CRC handling
```

## Binary Packet Protocol

### Packet Format (AudioPacket)

```
Offset  Size  Field
0       2     Magic (0xAF01)
2       1     Version (1)
3       1     Type (AUDIO_RX=0, AUDIO_TX=1, CONTROL=2, HEARTBEAT=3)
4       1     Flags (COMPRESSED, LOW_BANDWIDTH)
5       4     Sequence number
9       8     Timestamp (nanoseconds)
17      2     Payload length (0-16384 bytes)
19      N     Payload
19+N    4     CRC32
```

### Control Messages

| Message | Direction | Purpose |
|---------|-----------|---------|
| `CONNECT_REQUEST` | Client→Server | Handshake with callsign, buffer prefs |
| `AUDIO_CONFIG` | Server→Client | Sample rate, format, buffer thresholds |
| `CONNECT_ACCEPT/REJECT` | Server→Client | Connection result |
| `TX_GRANTED/DENIED/PREEMPTED` | Server→Client | TX channel arbitration |
| `CLIENTS_UPDATE` | Server→All | Connected client list broadcast |
| `HEARTBEAT` | Both | Keep-alive (5s interval, 10s timeout) |
| `LATENCY_PROBE/RESPONSE` | Both | Round-trip latency measurement |

## Audio Configuration

### Default Settings

```java
AudioStreamConfig.builder()
    .sampleRate(48000)           // 48 kHz (WSJT-X compatible)
    .bitsPerSample(16)           // 16-bit signed PCM
    .channels(2)                 // Stereo
    .frameDurationMs(20)         // 20ms per frame = 960 samples
    .bufferTargetMs(100)         // 100ms target buffer
    .bufferMinMs(40)             // Minimum before underrun warning
    .bufferMaxMs(300)            // Maximum before overrun
    .build();
```

### Optimization Profiles

```java
AudioStreamConfig.ft8Optimized();     // 40ms buffer, low-latency for digital
AudioStreamConfig.voiceOptimized();   // 120ms buffer, stable for SSB
AudioStreamConfig.lowBandwidth();     // 12kHz sample rate
```

## Ring Buffer (AudioRingBuffer)

Thread-safe circular buffer with jitter compensation:

```java
AudioRingBuffer buffer = new AudioRingBuffer(bytesPerSecond, targetMs, minMs, maxMs);

// Non-blocking write (overwrites oldest on overflow)
int written = buffer.write(pcmData);

// Blocking read with timeout (returns partial data when available)
int read = buffer.read(outputBuffer, timeoutMs);

// Monitoring
int levelMs = buffer.getBufferLevelMs();
boolean ready = buffer.hasReachedTargetLevel();
```

**Overrun Handling:** Oldest data dropped silently (maintains real-time flow)
**Underrun Handling:** Timeout returns 0 bytes (playback stalls)

## Server Architecture

### Multi-Client Audio Flow

```
┌─────────────────────────────────────────────────────┐
│             AudioStreamServer (Port 4533)           │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌───────────────────────────────────────────────┐ │
│  │ AudioBroadcaster (Single Capture Thread)      │ │
│  │ TargetDataLine (Radio USB Audio) → All Clients│ │
│  └───────────────────────────────────────────────┘ │
│                    │                                │
│  ┌─────────────────┼─────────────────────────────┐ │
│  │ ClientSession 1 ─┐                            │ │
│  │ ClientSession 2 ─┼─ Receives RX broadcast     │ │
│  │ ClientSession 3 ─┤                            │ │
│  │ ClientSession 4 ─┘                            │ │
│  └───────────────────────────────────────────────┘ │
│                                                     │
│  ┌───────────────────────────────────────────────┐ │
│  │ AudioMixer (TX Arbitration)                   │ │
│  │ Priority: NORMAL < HIGH < EXCLUSIVE           │ │
│  │ Idle timeout (500ms) → auto-release           │ │
│  │ Only ONE client TXs at a time                 │ │
│  └───────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### Server Usage

```java
AudioStreamServer server = new AudioStreamServer(4533, config);
server.setMaxClients(4);
server.setCaptureDevice(deviceInfo);
server.setPlaybackDevice(deviceInfo);

server.addAudioListener(pcmData -> {
    // Process RX audio (FFT, decoder, etc.)
});

server.addStreamListener(new AudioStreamListener() {
    void onClientConnected(String clientId, ClientInfo info) {}
    void onClientDisconnected(String clientId) {}
    void onStreamStarted() {}
    void onStreamStopped() {}
});

server.start();
// ... later
server.stop();
```

## Client Architecture

### Bidirectional Flow

```
┌─────────────────────────────────────────┐
│         AudioStreamClient               │
├─────────────────────────────────────────┤
│  RX Path (from Server):                 │
│  Socket → rxBuffer → SourceDataLine     │
│                                         │
│  TX Path (to Server):                   │
│  TargetDataLine → txBuffer → Socket     │
└─────────────────────────────────────────┘
```

### Client Usage

```java
AudioStreamClient client = new AudioStreamClient(host, 4533, config);
client.setCallsign("KJ5HST");
client.setOperatorName("Terrell");

client.addAudioListener(pcmData -> {
    // Process received audio
});

client.connect();  // Blocks until connected or timeout

// TX audio (if granted)
client.sendTxAudio(pcmData);

client.disconnect();
```

### Auto-Reconnect

```java
client.setAutoReconnect(true);
client.setMaxReconnectAttempts(5);
// Exponential backoff: 1s → 2s → 4s → ... → 30s max
```

## Audio Device Manager

### Device Discovery

```java
AudioDeviceManager manager = new AudioDeviceManager();

List<AudioDeviceInfo> all = manager.discoverDevices();
List<AudioDeviceInfo> capture = manager.discoverCaptureDevices();
List<AudioDeviceInfo> playback = manager.discoverPlaybackDevices();

// Find by pattern
AudioDeviceInfo device = manager.findDeviceByPattern(capture, "FTX-1", "USB Audio");

// Find virtual audio devices
AudioDeviceInfo virtual = manager.findVirtualCaptureDevice();
```

### Virtual Audio Detection

Platform-specific patterns for virtual audio routing:

| Platform | Patterns |
|----------|----------|
| macOS | `blackhole`, `soundflower`, `loopback` |
| Windows | `vb-audio`, `cable`, `virtual`, `voicemeeter` |
| Linux | `pulse`, `pipewire`, `jack`, `null` |

### Opening Audio Lines

```java
TargetDataLine captureLine = manager.openCaptureLine(device);
SourceDataLine playbackLine = manager.openPlaybackLine(device);
// Falls back to mono if stereo unavailable
```

## Thread Safety

- `AudioRingBuffer`: `ReentrantLock` with `notEmpty`/`notFull` conditions
- `AudioMixer.txLock`: Protects TX ownership changes
- `AudioProtocolHandler`: Synchronized `sendPacket()` for atomic writes
- `ConcurrentHashMap`: For sessions, clients, targets
- `CopyOnWriteArrayList`: For listeners (thread-safe iteration)
- `AtomicInteger`: Sequence counter
- `AtomicBoolean`: Running flags

## Error Recovery

| Error | Behavior |
|-------|----------|
| Single CRC error | Skip packet, continue |
| 5+ consecutive CRC errors | Close connection |
| Socket timeout | Null return, retry |
| Connection timeout (10s) | Reconnect |
| Buffer overrun | Drop oldest data |
| Buffer underrun | Return 0 bytes (stall) |

## Performance Characteristics

| Metric | Value |
|--------|-------|
| Audio bandwidth | 192 KB/s (48kHz, 16-bit, stereo) |
| Frame duration | 20ms (960 samples) |
| Bytes per frame | 3840 (mono) or 7680 (stereo) |
| Heartbeat interval | 5 seconds |
| Connection timeout | 10 seconds |
| Default max clients | 4 |

## Testing

```bash
mvn test                   # 44 unit tests
```

Tests cover:
- Ring buffer overflow/underflow behavior
- Packet serialization/CRC validation
- Device discovery mocking
- Client/server handshake
