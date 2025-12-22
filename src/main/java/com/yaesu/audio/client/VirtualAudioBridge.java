/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio.client;

import com.yaesu.audio.AudioDeviceInfo;
import com.yaesu.audio.AudioDeviceManager;

import java.util.List;

/**
 * Helper for integrating with platform-specific virtual audio devices.
 * <p>
 * Virtual audio devices are required on the client side to bridge
 * audio between this client and WSJT-X. Common options include:
 * <ul>
 *   <li>macOS: BlackHole, Soundflower, Loopback</li>
 *   <li>Windows: VB-Cable, Virtual Audio Cable, VoiceMeeter</li>
 *   <li>Linux: PulseAudio null sink, JACK</li>
 * </ul>
 * </p>
 */
public class VirtualAudioBridge {

    /**
     * Supported platforms.
     */
    public enum Platform {
        MACOS,
        WINDOWS,
        LINUX,
        UNKNOWN
    }

    private final AudioDeviceManager deviceManager;
    private final Platform platform;

    /**
     * Creates a new virtual audio bridge.
     */
    public VirtualAudioBridge(AudioDeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        this.platform = detectPlatform();
    }

    /**
     * Detects the current platform.
     */
    public static Platform detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            return Platform.MACOS;
        } else if (os.contains("win")) {
            return Platform.WINDOWS;
        } else if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return Platform.LINUX;
        }

        return Platform.UNKNOWN;
    }

    /**
     * Gets the current platform.
     */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * Finds virtual audio devices for capture.
     */
    public List<AudioDeviceInfo> findVirtualCaptureDevices() {
        return deviceManager.discoverCaptureDevices().stream()
            .filter(AudioDeviceInfo::isVirtual)
            .toList();
    }

    /**
     * Finds virtual audio devices for playback.
     */
    public List<AudioDeviceInfo> findVirtualPlaybackDevices() {
        return deviceManager.discoverPlaybackDevices().stream()
            .filter(AudioDeviceInfo::isVirtual)
            .toList();
    }

    /**
     * Attempts to find the best virtual device for capture.
     */
    public AudioDeviceInfo findBestCaptureDevice() {
        List<AudioDeviceInfo> devices = findVirtualCaptureDevices();
        if (devices.isEmpty()) {
            return null;
        }

        // Platform-specific preferences
        String[] preferredPatterns = getPreferredPatterns();
        for (String pattern : preferredPatterns) {
            for (AudioDeviceInfo device : devices) {
                if (device.getName().toLowerCase().contains(pattern)) {
                    return device;
                }
            }
        }

        // Return first virtual device found
        return devices.get(0);
    }

    /**
     * Attempts to find the best virtual device for playback.
     */
    public AudioDeviceInfo findBestPlaybackDevice() {
        List<AudioDeviceInfo> devices = findVirtualPlaybackDevices();
        if (devices.isEmpty()) {
            return null;
        }

        // Platform-specific preferences
        String[] preferredPatterns = getPreferredPatterns();
        for (String pattern : preferredPatterns) {
            for (AudioDeviceInfo device : devices) {
                if (device.getName().toLowerCase().contains(pattern)) {
                    return device;
                }
            }
        }

        // Return first virtual device found
        return devices.get(0);
    }

    /**
     * Checks if virtual audio is available.
     */
    public boolean isVirtualAudioAvailable() {
        return !findVirtualCaptureDevices().isEmpty() && !findVirtualPlaybackDevices().isEmpty();
    }

    /**
     * Gets installation instructions for the current platform.
     */
    public String getInstallInstructions() {
        switch (platform) {
            case MACOS:
                return """
                    === macOS Virtual Audio Setup ===

                    Option 1: BlackHole (Recommended, Free)
                    1. Install via Homebrew:
                       brew install blackhole-2ch

                    2. Or download from: https://existential.audio/blackhole/

                    3. After installation, "BlackHole 2ch" will appear in
                       System Settings > Sound as an audio device.

                    Option 2: Loopback (Commercial)
                    - Download from: https://rogueamoeba.com/loopback/

                    After installing virtual audio:
                    - In WSJT-X > Settings > Audio:
                      - Soundcard Input: BlackHole 2ch
                      - Soundcard Output: BlackHole 2ch
                    - In this client:
                      - Capture: BlackHole 2ch
                      - Playback: BlackHole 2ch
                    """;

            case WINDOWS:
                return """
                    === Windows Virtual Audio Setup ===

                    Option 1: VB-Cable (Free)
                    1. Download from: https://vb-audio.com/Cable/

                    2. Run installer as Administrator

                    3. After installation, "CABLE Input" and "CABLE Output"
                       will appear in Sound settings.

                    Option 2: VoiceMeeter (Free, more features)
                    - Download from: https://vb-audio.com/Voicemeeter/

                    After installing virtual audio:
                    - In WSJT-X > Settings > Audio:
                      - Soundcard Input: CABLE Output (VB-Audio)
                      - Soundcard Output: CABLE Input (VB-Audio)
                    - In this client:
                      - Capture: CABLE Input (captures what WSJT-X outputs)
                      - Playback: CABLE Output (plays to WSJT-X input)
                    """;

            case LINUX:
                return """
                    === Linux Virtual Audio Setup ===

                    Option 1: PulseAudio Null Sink (Built-in)
                    1. Create a virtual sink:
                       pactl load-module module-null-sink sink_name=virtual sink_properties=device.description=Virtual_Audio

                    2. Create a virtual source (loopback):
                       pactl load-module module-loopback source=virtual.monitor

                    3. The virtual sink will appear in pavucontrol or
                       sound settings.

                    Option 2: JACK (Low latency)
                    1. Install JACK:
                       sudo apt install jackd2 qjackctl

                    2. Start JACK and use qjackctl to make connections.

                    Option 3: PipeWire (Modern distros)
                    - PipeWire often includes virtual device support.
                      Check your distro's documentation.

                    After setting up virtual audio:
                    - In WSJT-X > Settings > Audio:
                      - Soundcard Input: Virtual Audio Monitor
                      - Soundcard Output: Virtual Audio
                    - In this client:
                      - Capture: Virtual Audio Monitor
                      - Playback: Virtual Audio
                    """;

            default:
                return "Unknown platform. Please set up a virtual audio device manually.";
        }
    }

    /**
     * Gets WSJT-X configuration instructions.
     */
    public String getWSJTXInstructions() {
        String deviceName = getPreferredDeviceName();

        return String.format("""
            === WSJT-X Configuration ===

            1. Open WSJT-X
            2. Go to File > Settings > Audio

            3. Configure audio devices:
               - Soundcard Input:  %s
               - Soundcard Output: %s

            4. Go to File > Settings > Radio

            5. Configure radio connection:
               - Rig: Hamlib NET rigctl
               - Network Server: [ftx1-audio server IP]:4532
               - Example: 192.168.1.100:4532

            6. Click "Test CAT" to verify connection

            7. Start this audio client and connect to:
               [ftx1-audio server IP]:4533

            The audio path will be:
            FTX-1 Radio <-> ftx1-audio Server <-> This Client <-> Virtual Audio <-> WSJT-X
            """, deviceName, deviceName);
    }

    /**
     * Generates a diagnostic report.
     */
    public String generateDiagnosticReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Virtual Audio Diagnostic Report ===\n\n");
        sb.append("Platform: ").append(platform).append("\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n\n");

        List<AudioDeviceInfo> captureDevices = deviceManager.discoverCaptureDevices();
        List<AudioDeviceInfo> playbackDevices = deviceManager.discoverPlaybackDevices();

        sb.append("Capture Devices (").append(captureDevices.size()).append("):\n");
        for (AudioDeviceInfo device : captureDevices) {
            sb.append("  - ").append(device.getName());
            if (device.isVirtual()) {
                sb.append(" [VIRTUAL]");
            }
            sb.append("\n");
        }

        sb.append("\nPlayback Devices (").append(playbackDevices.size()).append("):\n");
        for (AudioDeviceInfo device : playbackDevices) {
            sb.append("  - ").append(device.getName());
            if (device.isVirtual()) {
                sb.append(" [VIRTUAL]");
            }
            sb.append("\n");
        }

        sb.append("\nVirtual Audio Available: ");
        sb.append(isVirtualAudioAvailable() ? "YES" : "NO");
        sb.append("\n");

        if (!isVirtualAudioAvailable()) {
            sb.append("\n").append(getInstallInstructions());
        }

        return sb.toString();
    }

    private String[] getPreferredPatterns() {
        switch (platform) {
            case MACOS:
                return new String[]{"blackhole", "loopback", "soundflower"};
            case WINDOWS:
                return new String[]{"cable", "vb-audio", "voicemeeter"};
            case LINUX:
                return new String[]{"virtual", "null", "jack"};
            default:
                return new String[]{};
        }
    }

    private String getPreferredDeviceName() {
        switch (platform) {
            case MACOS:
                return "BlackHole 2ch";
            case WINDOWS:
                return "CABLE Input/Output (VB-Audio)";
            case LINUX:
                return "Virtual Audio";
            default:
                return "[Virtual Audio Device]";
        }
    }
}
