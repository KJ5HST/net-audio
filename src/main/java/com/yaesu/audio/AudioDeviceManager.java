/*
 * Net-Audio Streaming Library
 * Copyright (c) 2025 by Terrell Deppe (KJ5HST)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License.
 */
package com.yaesu.audio;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages audio device discovery and selection.
 * <p>
 * Discovers audio devices on the system and identifies virtual audio devices
 * (VB-Cable, BlackHole, PulseAudio) for network audio bridging.
 * </p>
 */
public class AudioDeviceManager {

    // Virtual audio device patterns by platform
    private static final String[] VIRTUAL_PATTERNS_MACOS = {
        "blackhole", "soundflower", "loopback"
    };

    private static final String[] VIRTUAL_PATTERNS_WINDOWS = {
        "vb-audio", "cable", "virtual", "voicemeeter"
    };

    private static final String[] VIRTUAL_PATTERNS_LINUX = {
        "pulse", "pipewire", "jack", "null"
    };

    private final AudioStreamConfig config;

    /**
     * Creates a new AudioDeviceManager with default configuration.
     */
    public AudioDeviceManager() {
        this(new AudioStreamConfig());
    }

    /**
     * Creates a new AudioDeviceManager with the specified configuration.
     *
     * @param config the audio stream configuration
     */
    public AudioDeviceManager(AudioStreamConfig config) {
        this.config = config;
    }

    /**
     * Discovers all audio devices that support the configured format.
     *
     * @return list of available audio devices
     */
    public List<AudioDeviceInfo> discoverDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        AudioFormat format = config.toAudioFormat();

        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);

            // Check for capture capability
            if (supportsCapture(mixer, format)) {
                AudioDeviceInfo.DeviceType type = classifyDevice(mixerInfo);
                devices.add(new AudioDeviceInfo(mixerInfo, type, AudioDeviceInfo.Capability.CAPTURE));
            }

            // Check for playback capability
            if (supportsPlayback(mixer, format)) {
                AudioDeviceInfo.DeviceType type = classifyDevice(mixerInfo);
                // Check if we already added this as capture - if so, upgrade to DUPLEX
                boolean found = false;
                for (int i = 0; i < devices.size(); i++) {
                    AudioDeviceInfo existing = devices.get(i);
                    if (existing.getMixerInfo().equals(mixerInfo)) {
                        if (existing.getCapability() == AudioDeviceInfo.Capability.CAPTURE) {
                            devices.set(i, new AudioDeviceInfo(mixerInfo, type, AudioDeviceInfo.Capability.DUPLEX));
                        }
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    devices.add(new AudioDeviceInfo(mixerInfo, type, AudioDeviceInfo.Capability.PLAYBACK));
                }
            }
        }

        return devices;
    }

    /**
     * Discovers capture-capable devices only.
     *
     * @return list of devices that can capture audio
     */
    public List<AudioDeviceInfo> discoverCaptureDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        for (AudioDeviceInfo device : discoverDevices()) {
            if (device.supportsCapture()) {
                devices.add(device);
            }
        }
        return devices;
    }

    /**
     * Discovers playback-capable devices only.
     *
     * @return list of devices that can play audio
     */
    public List<AudioDeviceInfo> discoverPlaybackDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        for (AudioDeviceInfo device : discoverDevices()) {
            if (device.supportsPlayback()) {
                devices.add(device);
            }
        }
        return devices;
    }

    /**
     * Finds a device matching any of the specified patterns.
     * <p>
     * Pattern matching is case-insensitive and matches against both
     * device name and description.
     * </p>
     *
     * @param devices the list of devices to search
     * @param patterns one or more patterns to match (case-insensitive)
     * @return the first matching device, or null if not found
     */
    public AudioDeviceInfo findDeviceByPattern(List<AudioDeviceInfo> devices, String... patterns) {
        for (AudioDeviceInfo device : devices) {
            if (matchesPatterns(device, patterns)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Attempts to find a virtual audio device for capture.
     *
     * @return the virtual device info, or null if not found
     */
    public AudioDeviceInfo findVirtualCaptureDevice() {
        String[] patterns = getVirtualPatterns();
        return findDeviceByPattern(discoverCaptureDevices(), patterns);
    }

    /**
     * Attempts to find a virtual audio device for playback.
     *
     * @return the virtual device info, or null if not found
     */
    public AudioDeviceInfo findVirtualPlaybackDevice() {
        String[] patterns = getVirtualPatterns();
        return findDeviceByPattern(discoverPlaybackDevices(), patterns);
    }

    /**
     * Opens a TargetDataLine for audio capture from the specified device.
     * <p>
     * If the device doesn't support stereo capture, this will attempt to open
     * a mono line instead. The caller should check {@link TargetDataLine#getFormat()}
     * to determine the actual format and convert to stereo if needed.
     * </p>
     *
     * @param device the device to open
     * @return the opened TargetDataLine
     * @throws LineUnavailableException if the line cannot be opened
     */
    public TargetDataLine openCaptureLine(AudioDeviceInfo device) throws LineUnavailableException {
        AudioFormat format = config.toAudioFormat();
        Mixer mixer = AudioSystem.getMixer(device.getMixerInfo());

        // Try stereo first
        DataLine.Info stereoInfo = new DataLine.Info(TargetDataLine.class, format);
        if (mixer.isLineSupported(stereoInfo)) {
            System.out.println("[AudioDeviceManager] Opening capture (stereo): " + device.getName());
            TargetDataLine line = (TargetDataLine) mixer.getLine(stereoInfo);
            line.open(format);
            System.out.println("[AudioDeviceManager] Capture line opened: " + line.getFormat());
            return line;
        }

        // Fall back to mono (many microphones are mono only)
        AudioFormat monoFormat = new AudioFormat(
            format.getEncoding(),
            format.getSampleRate(),
            format.getSampleSizeInBits(),
            1, // mono
            format.getFrameSize() / 2,
            format.getFrameRate(),
            format.isBigEndian()
        );
        DataLine.Info monoInfo = new DataLine.Info(TargetDataLine.class, monoFormat);
        if (mixer.isLineSupported(monoInfo)) {
            System.out.println("[AudioDeviceManager] Opening capture (mono, will convert to stereo): " + device.getName());
            TargetDataLine line = (TargetDataLine) mixer.getLine(monoInfo);
            line.open(monoFormat);
            System.out.println("[AudioDeviceManager] Capture line opened: " + line.getFormat());
            return line;
        }

        throw new LineUnavailableException("Device does not support required audio format: " + device.getName());
    }

    /**
     * Opens a SourceDataLine for audio playback to the specified device.
     *
     * @param device the device to open
     * @return the opened SourceDataLine
     * @throws LineUnavailableException if the line cannot be opened
     */
    public SourceDataLine openPlaybackLine(AudioDeviceInfo device) throws LineUnavailableException {
        AudioFormat format = config.toAudioFormat();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        System.out.println("[AudioDeviceManager] Opening playback: " + device.getName() +
            ", format: " + format.getSampleRate() + "Hz/" + format.getSampleSizeInBits() + "bit/" + format.getChannels() + "ch");

        Mixer mixer = AudioSystem.getMixer(device.getMixerInfo());
        System.out.println("[AudioDeviceManager] Mixer: " + mixer.getMixerInfo().getName());

        SourceDataLine line = (SourceDataLine) mixer.getLine(info);
        line.open(format);
        System.out.println("[AudioDeviceManager] Line opened: " + line.getFormat());
        return line;
    }

    /**
     * Gets the configuration used by this manager.
     */
    public AudioStreamConfig getConfig() {
        return config;
    }

    /**
     * Gets platform-specific setup instructions for virtual audio devices.
     *
     * @return setup instructions as a string
     */
    public static String getVirtualAudioSetupInstructions() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            return """
                macOS Virtual Audio Setup:
                1. Install BlackHole: brew install blackhole-2ch
                2. In System Settings > Sound, BlackHole should appear
                3. Select BlackHole 2ch for both capture and playback
                """;
        } else if (os.contains("win")) {
            return """
                Windows Virtual Audio Setup:
                1. Download VB-Cable from https://vb-audio.com/Cable/
                2. Install VB-Cable (requires admin rights)
                3. Select CABLE Input for capture, CABLE Output for playback
                """;
        } else {
            return """
                Linux Virtual Audio Setup:
                Using PulseAudio:
                1. Create a null sink: pactl load-module module-null-sink sink_name=virtual
                2. Select the virtual sink for capture/playback

                Using JACK:
                1. Start JACK server: jackd -d alsa
                2. Use qjackctl to make audio connections
                """;
        }
    }

    private boolean supportsCapture(Mixer mixer, AudioFormat format) {
        DataLine.Info targetInfo = new DataLine.Info(TargetDataLine.class, format);
        if (mixer.isLineSupported(targetInfo)) {
            return true;
        }
        // Also check for mono capture - many microphones are mono only
        // We'll convert mono to stereo during streaming if needed
        if (format.getChannels() == 2) {
            AudioFormat monoFormat = new AudioFormat(
                format.getEncoding(),
                format.getSampleRate(),
                format.getSampleSizeInBits(),
                1, // mono
                format.getFrameSize() / 2, // half frame size for mono
                format.getFrameRate(),
                format.isBigEndian()
            );
            DataLine.Info monoInfo = new DataLine.Info(TargetDataLine.class, monoFormat);
            return mixer.isLineSupported(monoInfo);
        }
        return false;
    }

    private boolean supportsPlayback(Mixer mixer, AudioFormat format) {
        DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, format);
        return mixer.isLineSupported(sourceInfo);
    }

    private AudioDeviceInfo.DeviceType classifyDevice(Mixer.Info mixerInfo) {
        String name = mixerInfo.getName().toLowerCase();
        String desc = mixerInfo.getDescription().toLowerCase();
        String combined = name + " " + desc;

        // Check for virtual device patterns
        String[] virtualPatterns = getVirtualPatterns();
        for (String pattern : virtualPatterns) {
            if (combined.contains(pattern)) {
                return AudioDeviceInfo.DeviceType.VIRTUAL;
            }
        }

        // Default to hardware for physical devices
        return AudioDeviceInfo.DeviceType.HARDWARE;
    }

    private boolean matchesPatterns(AudioDeviceInfo device, String[] patterns) {
        String name = device.getName().toLowerCase();
        String desc = device.getDescription().toLowerCase();
        String combined = name + " " + desc;

        for (String pattern : patterns) {
            if (combined.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String[] getVirtualPatterns() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            return VIRTUAL_PATTERNS_MACOS;
        } else if (os.contains("win")) {
            return VIRTUAL_PATTERNS_WINDOWS;
        } else {
            return VIRTUAL_PATTERNS_LINUX;
        }
    }
}
