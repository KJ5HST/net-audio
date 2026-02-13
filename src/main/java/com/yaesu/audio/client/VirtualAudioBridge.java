/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio.client;

import com.yaesu.audio.AudioDeviceInfo;
import com.yaesu.audio.AudioDeviceManager;
import com.yaesu.audio.AudioStreamConfig;

import javax.sound.sampled.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger logger = Logger.getLogger(VirtualAudioBridge.class.getName());

    /** Required sample rate for WSJT-X compatibility */
    private static final int REQUIRED_SAMPLE_RATE = AudioStreamConfig.DEFAULT_SAMPLE_RATE;

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
    private final AudioStreamConfig config;

    /**
     * Creates a new virtual audio bridge.
     */
    public VirtualAudioBridge(AudioDeviceManager deviceManager) {
        this(deviceManager, new AudioStreamConfig());
    }

    /**
     * Creates a new virtual audio bridge with custom configuration.
     */
    public VirtualAudioBridge(AudioDeviceManager deviceManager, AudioStreamConfig config) {
        this.deviceManager = deviceManager;
        this.config = config;
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
     * Validates that the device supports the required sample rate (48kHz).
     */
    public AudioDeviceInfo findBestCaptureDevice() {
        List<AudioDeviceInfo> devices = findVirtualCaptureDevices();
        if (devices.isEmpty()) {
            return null;
        }

        // Platform-specific preferences, with sample rate validation
        String[] preferredPatterns = getPreferredPatterns();
        for (String pattern : preferredPatterns) {
            for (AudioDeviceInfo device : devices) {
                if (device.getName().toLowerCase().contains(pattern)) {
                    if (supportsSampleRate(device, true)) {
                        return device;
                    } else {
                        logger.warning("Virtual audio device '" + device.getName() +
                            "' does not support required sample rate (" + config.getSampleRate() + " Hz)");
                    }
                }
            }
        }

        // Fall back to first device that supports required sample rate
        for (AudioDeviceInfo device : devices) {
            if (supportsSampleRate(device, true)) {
                return device;
            }
        }

        // Last resort: return first device even without validation
        logger.warning("No virtual capture device found with verified " +
            config.getSampleRate() + " Hz support. Using first available device.");
        return devices.get(0);
    }

    /**
     * Attempts to find the best virtual device for playback.
     * Validates that the device supports the required sample rate (48kHz).
     */
    public AudioDeviceInfo findBestPlaybackDevice() {
        List<AudioDeviceInfo> devices = findVirtualPlaybackDevices();
        if (devices.isEmpty()) {
            return null;
        }

        // Platform-specific preferences, with sample rate validation
        String[] preferredPatterns = getPreferredPatterns();
        for (String pattern : preferredPatterns) {
            for (AudioDeviceInfo device : devices) {
                if (device.getName().toLowerCase().contains(pattern)) {
                    if (supportsSampleRate(device, false)) {
                        return device;
                    } else {
                        logger.warning("Virtual audio device '" + device.getName() +
                            "' does not support required sample rate (" + config.getSampleRate() + " Hz)");
                    }
                }
            }
        }

        // Fall back to first device that supports required sample rate
        for (AudioDeviceInfo device : devices) {
            if (supportsSampleRate(device, false)) {
                return device;
            }
        }

        // Last resort: return first device even without validation
        logger.warning("No virtual playback device found with verified " +
            config.getSampleRate() + " Hz support. Using first available device.");
        return devices.get(0);
    }

    /**
     * Checks if a device supports the required sample rate.
     *
     * @param device the device to check
     * @param forCapture true for capture device, false for playback
     * @return true if the device supports the required sample rate
     */
    public boolean supportsSampleRate(AudioDeviceInfo device, boolean forCapture) {
        try {
            Mixer mixer = AudioSystem.getMixer(device.getMixerInfo());
            AudioFormat requiredFormat = config.toAudioFormat();

            // Get the appropriate line info
            Line.Info[] lineInfos;
            if (forCapture) {
                lineInfos = mixer.getTargetLineInfo();
            } else {
                lineInfos = mixer.getSourceLineInfo();
            }

            for (Line.Info lineInfo : lineInfos) {
                if (lineInfo instanceof DataLine.Info) {
                    DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                    if (dataLineInfo.isFormatSupported(requiredFormat)) {
                        return true;
                    }

                    // Also check if any supported format matches our sample rate
                    AudioFormat[] formats = dataLineInfo.getFormats();
                    for (AudioFormat format : formats) {
                        if (format.getSampleRate() == AudioSystem.NOT_SPECIFIED ||
                            format.getSampleRate() == config.getSampleRate()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Could not verify sample rate support for " + device.getName() + ": " + e.getMessage());
        }

        return false;
    }

    /**
     * Validates that the given device supports the required audio format.
     * Logs warnings if validation fails but does not throw exceptions.
     *
     * @param device the device to validate
     * @param forCapture true for capture device, false for playback
     * @return true if validation passed, false otherwise
     */
    public boolean validateDevice(AudioDeviceInfo device, boolean forCapture) {
        if (device == null) {
            return false;
        }

        if (!supportsSampleRate(device, forCapture)) {
            logger.warning(String.format(
                "Device '%s' may not support required format: %d Hz, %d-bit, %d channel(s). " +
                "Audio quality may be affected.",
                device.getName(), config.getSampleRate(),
                config.getBitsPerSample(), config.getChannels()));
            return false;
        }

        return true;
    }

    /**
     * Checks if virtual audio is available.
     */
    public boolean isVirtualAudioAvailable() {
        return !findVirtualCaptureDevices().isEmpty() && !findVirtualPlaybackDevices().isEmpty();
    }

    /**
     * Gets installation instructions for the current platform.
     * Includes detailed sample rate configuration steps.
     */
    public String getInstallInstructions() {
        int sampleRate = config.getSampleRate();
        int bits = config.getBitsPerSample();
        int channels = config.getChannels();
        String channelStr = channels == 1 ? "Mono" : "Stereo";

        switch (platform) {
            case MACOS:
                return String.format("""
                    === macOS Virtual Audio Setup ===

                    STEP 1: Install BlackHole (Recommended, Free)
                    ─────────────────────────────────────────────
                    Option A - Homebrew:
                       brew install blackhole-2ch

                    Option B - Direct download:
                       https://existential.audio/blackhole/

                    STEP 2: Verify Sample Rate (CRITICAL)
                    ─────────────────────────────────────────────
                    BlackHole must be set to %d Hz for WSJT-X compatibility.

                    1. Open: /Applications/Utilities/Audio MIDI Setup.app
                    2. Find "BlackHole 2ch" in the device list (left panel)
                    3. Click on it to select it
                    4. In the right panel, check the "Format" dropdown
                       - Should show "%d.0 Hz" (48000.0 Hz is typically the default)
                       - If not, select a format that includes %d Hz

                    STEP 3: Create Multi-Output Device (Optional but Recommended)
                    ─────────────────────────────────────────────
                    This lets you monitor audio while streaming:

                    1. In Audio MIDI Setup, click "+" at bottom left
                    2. Select "Create Multi-Output Device"
                    3. Check both "BlackHole 2ch" and your speakers/headphones
                    4. Right-click the new device → "Use This Device For Sound Output"

                    STEP 4: Configure Applications
                    ─────────────────────────────────────────────
                    WSJT-X Settings > Audio:
                      - Soundcard Input:  BlackHole 2ch
                      - Soundcard Output: BlackHole 2ch

                    ftx1-hamlib Audio Client:
                      - Capture:  BlackHole 2ch
                      - Playback: BlackHole 2ch

                    Alternative: Loopback (Commercial, $99)
                    - More flexible routing: https://rogueamoeba.com/loopback/
                    """, sampleRate, sampleRate, sampleRate);

            case WINDOWS:
                return String.format("""
                    === Windows Virtual Audio Setup ===

                    STEP 1: Install VB-Cable (Free)
                    ─────────────────────────────────────────────
                    1. Download from: https://vb-audio.com/Cable/
                    2. Extract the ZIP file
                    3. Right-click VBCABLE_Setup_x64.exe → Run as Administrator
                    4. Reboot when prompted

                    STEP 2: Configure Sample Rate (CRITICAL)
                    ─────────────────────────────────────────────
                    VB-Cable defaults to 44100 Hz but WSJT-X requires %d Hz.

                    Configure CABLE Output (Recording):
                    1. Right-click speaker icon in taskbar → Sounds
                    2. Go to "Recording" tab
                    3. Right-click "CABLE Output" → Properties
                    4. Go to "Advanced" tab
                    5. Set "Default Format" to: %d Hz, %d bit, %s
                    6. Uncheck both "Exclusive Mode" options
                    7. Click OK

                    Configure CABLE Input (Playback):
                    1. Go to "Playback" tab
                    2. Right-click "CABLE Input" → Properties
                    3. Go to "Advanced" tab
                    4. Set "Default Format" to: %d Hz, %d bit, %s
                    5. Uncheck both "Exclusive Mode" options
                    6. Click OK

                    STEP 3: Configure Applications
                    ─────────────────────────────────────────────
                    WSJT-X Settings > Audio:
                      - Soundcard Input:  CABLE Output (VB-Audio Virtual Cable)
                      - Soundcard Output: CABLE Input (VB-Audio Virtual Cable)

                    ftx1-hamlib Audio Client:
                      - Capture:  CABLE Input (captures WSJT-X TX audio)
                      - Playback: CABLE Output (sends RX audio to WSJT-X)

                    Alternative: VoiceMeeter (Free, more features)
                    - Download from: https://vb-audio.com/Voicemeeter/
                    """, sampleRate, sampleRate, bits, channelStr, sampleRate, bits, channelStr);

            case LINUX:
                return String.format("""
                    === Linux Virtual Audio Setup ===

                    STEP 1: Create Virtual Audio Device
                    ─────────────────────────────────────────────

                    Option A - PulseAudio (most distros):

                    # Create null sink with correct sample rate
                    pactl load-module module-null-sink \\
                        sink_name=ftx1_audio \\
                        sink_properties=device.description=FTX1_Virtual_Audio \\
                        rate=%d \\
                        channels=%d \\
                        format=s%dle

                    # Create loopback for bidirectional audio
                    pactl load-module module-loopback \\
                        source=ftx1_audio.monitor \\
                        sink=ftx1_audio \\
                        latency_msec=20

                    Option B - PipeWire (Fedora, Ubuntu 22.10+):

                    # PipeWire is PulseAudio-compatible, same commands work
                    # Or use pw-cli for native PipeWire:
                    pw-cli create-node adapter \\
                        factory.name=support.null-audio-sink \\
                        node.name=ftx1_audio \\
                        media.class=Audio/Sink \\
                        audio.rate=%d \\
                        audio.channels=%d

                    STEP 2: Make Persistent (Optional)
                    ─────────────────────────────────────────────
                    Add to ~/.config/pulse/default.pa or /etc/pulse/default.pa:

                    load-module module-null-sink sink_name=ftx1_audio rate=%d channels=%d format=s%dle sink_properties=device.description=FTX1_Virtual_Audio
                    load-module module-loopback source=ftx1_audio.monitor sink=ftx1_audio latency_msec=20

                    STEP 3: Verify with pavucontrol
                    ─────────────────────────────────────────────
                    sudo apt install pavucontrol  # or dnf/pacman
                    pavucontrol
                    # Check "Output Devices" and "Input Devices" tabs

                    STEP 4: Configure Applications
                    ─────────────────────────────────────────────
                    WSJT-X Settings > Audio:
                      - Soundcard Input:  FTX1_Virtual_Audio Monitor
                      - Soundcard Output: FTX1_Virtual_Audio

                    ftx1-hamlib Audio Client:
                      - Capture:  Monitor of FTX1_Virtual_Audio
                      - Playback: FTX1_Virtual_Audio

                    TIP: Use this client's --auto-configure flag to set up automatically
                    """, sampleRate, channels, bits, sampleRate, channels, sampleRate, channels, bits);

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

    // ========== Configuration Verification ==========

    /**
     * Result of verifying a virtual audio device configuration.
     */
    public static class VerificationResult {
        private final boolean success;
        private final String deviceName;
        private final int actualSampleRate;
        private final int requiredSampleRate;
        private final int actualChannels;
        private final int requiredChannels;
        private final List<String> issues;
        private final List<String> suggestions;

        public VerificationResult(boolean success, String deviceName,
                                  int actualSampleRate, int requiredSampleRate,
                                  int actualChannels, int requiredChannels,
                                  List<String> issues, List<String> suggestions) {
            this.success = success;
            this.deviceName = deviceName;
            this.actualSampleRate = actualSampleRate;
            this.requiredSampleRate = requiredSampleRate;
            this.actualChannels = actualChannels;
            this.requiredChannels = requiredChannels;
            this.issues = issues != null ? issues : new ArrayList<>();
            this.suggestions = suggestions != null ? suggestions : new ArrayList<>();
        }

        public boolean isSuccess() { return success; }
        public String getDeviceName() { return deviceName; }
        public int getActualSampleRate() { return actualSampleRate; }
        public int getRequiredSampleRate() { return requiredSampleRate; }
        public int getActualChannels() { return actualChannels; }
        public int getRequiredChannels() { return requiredChannels; }
        public List<String> getIssues() { return issues; }
        public List<String> getSuggestions() { return suggestions; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Device: ").append(deviceName).append("\n");
            sb.append("Status: ").append(success ? "OK" : "CONFIGURATION NEEDED").append("\n");
            sb.append("Sample Rate: ").append(actualSampleRate).append(" Hz");
            if (actualSampleRate != requiredSampleRate) {
                sb.append(" (required: ").append(requiredSampleRate).append(" Hz)");
            }
            sb.append("\n");
            sb.append("Channels: ").append(actualChannels);
            if (actualChannels != requiredChannels) {
                sb.append(" (required: ").append(requiredChannels).append(")");
            }
            sb.append("\n");

            if (!issues.isEmpty()) {
                sb.append("\nIssues:\n");
                for (String issue : issues) {
                    sb.append("  ! ").append(issue).append("\n");
                }
            }

            if (!suggestions.isEmpty()) {
                sb.append("\nSuggestions:\n");
                for (String suggestion : suggestions) {
                    sb.append("  → ").append(suggestion).append("\n");
                }
            }

            return sb.toString();
        }
    }

    /**
     * Verifies that a device is properly configured for the required audio format.
     * Checks sample rate and channel configuration.
     *
     * @param device the device to verify
     * @param forCapture true for capture device, false for playback
     * @return verification result with details and suggestions
     */
    public VerificationResult verifyDeviceConfiguration(AudioDeviceInfo device, boolean forCapture) {
        if (device == null) {
            return new VerificationResult(false, "null", 0, config.getSampleRate(),
                    0, config.getChannels(),
                    List.of("No device specified"),
                    List.of("Select a virtual audio device"));
        }

        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        int detectedSampleRate = 0;
        int detectedChannels = 0;
        boolean formatSupported = false;

        try {
            Mixer mixer = AudioSystem.getMixer(device.getMixerInfo());
            AudioFormat requiredFormat = config.toAudioFormat();

            // Get the appropriate line info
            Line.Info[] lineInfos = forCapture ? mixer.getTargetLineInfo() : mixer.getSourceLineInfo();

            for (Line.Info lineInfo : lineInfos) {
                if (lineInfo instanceof DataLine.Info) {
                    DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;

                    // Check if exact format is supported
                    if (dataLineInfo.isFormatSupported(requiredFormat)) {
                        formatSupported = true;
                        detectedSampleRate = config.getSampleRate();
                        detectedChannels = config.getChannels();
                        break;
                    }

                    // Check available formats
                    AudioFormat[] formats = dataLineInfo.getFormats();
                    for (AudioFormat format : formats) {
                        float rate = format.getSampleRate();
                        int channels = format.getChannels();

                        // Track what we found
                        if (rate != AudioSystem.NOT_SPECIFIED && rate > detectedSampleRate) {
                            detectedSampleRate = (int) rate;
                        }
                        if (channels != AudioSystem.NOT_SPECIFIED && channels > detectedChannels) {
                            detectedChannels = channels;
                        }

                        // Check for match
                        if ((rate == AudioSystem.NOT_SPECIFIED || rate == config.getSampleRate()) &&
                            (channels == AudioSystem.NOT_SPECIFIED || channels == config.getChannels())) {
                            formatSupported = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            issues.add("Could not query device: " + e.getMessage());
        }

        // Analyze results
        if (detectedSampleRate == 0) {
            detectedSampleRate = -1;  // Unknown
            issues.add("Could not determine device sample rate");
        } else if (detectedSampleRate != config.getSampleRate() && !formatSupported) {
            issues.add(String.format("Sample rate mismatch: device is %d Hz, required %d Hz",
                    detectedSampleRate, config.getSampleRate()));
            suggestions.addAll(getSampleRateConfigurationSuggestions(device));
        }

        if (detectedChannels == 0) {
            detectedChannels = -1;  // Unknown
        } else if (detectedChannels != config.getChannels() && !formatSupported) {
            issues.add(String.format("Channel mismatch: device has %d channels, required %d",
                    detectedChannels, config.getChannels()));
        }

        boolean success = formatSupported || issues.isEmpty();

        return new VerificationResult(success, device.getName(),
                detectedSampleRate, config.getSampleRate(),
                detectedChannels, config.getChannels(),
                issues, suggestions);
    }

    /**
     * Gets platform-specific suggestions for configuring sample rate.
     */
    private List<String> getSampleRateConfigurationSuggestions(AudioDeviceInfo device) {
        List<String> suggestions = new ArrayList<>();
        String deviceName = device.getName().toLowerCase();

        switch (platform) {
            case MACOS:
                suggestions.add("Open Audio MIDI Setup (/Applications/Utilities/Audio MIDI Setup.app)");
                suggestions.add("Select '" + device.getName() + "' and set Format to " + config.getSampleRate() + " Hz");
                break;

            case WINDOWS:
                suggestions.add("Open Sound settings (right-click speaker icon → Sounds)");
                if (deviceName.contains("cable")) {
                    suggestions.add("Go to Recording tab → CABLE Output → Properties → Advanced");
                    suggestions.add("Set Default Format to: " + config.getSampleRate() + " Hz, " +
                            config.getBitsPerSample() + " bit");
                    suggestions.add("Also configure Playback tab → CABLE Input with same settings");
                } else {
                    suggestions.add("Find '" + device.getName() + "' → Properties → Advanced");
                    suggestions.add("Set Default Format to: " + config.getSampleRate() + " Hz");
                }
                break;

            case LINUX:
                suggestions.add("Recreate the virtual sink with correct sample rate:");
                suggestions.add("  pactl unload-module module-null-sink");
                suggestions.add("  pactl load-module module-null-sink sink_name=ftx1_audio rate=" +
                        config.getSampleRate() + " channels=" + config.getChannels());
                break;
        }

        return suggestions;
    }

    // ========== Linux Auto-Configuration ==========

    /**
     * Result of attempting to auto-configure virtual audio.
     */
    public static class ConfigurationResult {
        private final boolean success;
        private final String message;
        private final String sinkName;
        private final List<String> commands;

        public ConfigurationResult(boolean success, String message, String sinkName, List<String> commands) {
            this.success = success;
            this.message = message;
            this.sinkName = sinkName;
            this.commands = commands != null ? commands : new ArrayList<>();
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getSinkName() { return sinkName; }
        public List<String> getCommands() { return commands; }
    }

    /**
     * Attempts to automatically configure virtual audio for Linux.
     * Creates a PulseAudio/PipeWire null sink with the correct sample rate.
     *
     * @return configuration result
     */
    public ConfigurationResult autoConfigureLinux() {
        if (platform != Platform.LINUX) {
            return new ConfigurationResult(false,
                    "Auto-configuration is only supported on Linux",
                    null, null);
        }

        String sinkName = "ftx1_audio";
        List<String> commands = new ArrayList<>();

        // First, check if sink already exists and remove it
        String checkCmd = "pactl list short sinks | grep " + sinkName;
        commands.add(checkCmd);

        try {
            ProcessBuilder checkPb = new ProcessBuilder("bash", "-c", checkCmd);
            Process checkProcess = checkPb.start();
            int checkResult = checkProcess.waitFor();

            if (checkResult == 0) {
                // Sink exists, unload it first
                String unloadCmd = "pactl unload-module module-null-sink";
                commands.add(unloadCmd);
                ProcessBuilder unloadPb = new ProcessBuilder("bash", "-c",
                        "pactl list short modules | grep module-null-sink | grep " + sinkName +
                        " | cut -f1 | xargs -r pactl unload-module");
                unloadPb.start().waitFor();
            }
        } catch (Exception e) {
            // Continue anyway
        }

        // Create the null sink with correct parameters
        String createSinkCmd = String.format(
                "pactl load-module module-null-sink sink_name=%s " +
                "sink_properties=device.description=FTX1_Virtual_Audio " +
                "rate=%d channels=%d format=s%dle",
                sinkName, config.getSampleRate(), config.getChannels(), config.getBitsPerSample());
        commands.add(createSinkCmd);

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", createSinkCmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.readLine();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                return new ConfigurationResult(false,
                        "Failed to create null sink. Is PulseAudio/PipeWire running? Output: " + output,
                        sinkName, commands);
            }

            // Create loopback for monitor
            String loopbackCmd = String.format(
                    "pactl load-module module-loopback source=%s.monitor sink=%s latency_msec=20",
                    sinkName, sinkName);
            commands.add(loopbackCmd);

            pb = new ProcessBuilder("bash", "-c", loopbackCmd);
            process = pb.start();
            exitCode = process.waitFor();

            if (exitCode != 0) {
                logger.warning("Loopback creation failed, but sink was created");
            }

            // Verify creation
            String verifyCmd = "pactl list short sinks | grep " + sinkName;
            commands.add(verifyCmd);
            pb = new ProcessBuilder("bash", "-c", verifyCmd);
            process = pb.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            output = reader.readLine();
            exitCode = process.waitFor();

            if (exitCode == 0 && output != null && output.contains(sinkName)) {
                return new ConfigurationResult(true,
                        "Virtual audio device created successfully: " + sinkName,
                        sinkName, commands);
            } else {
                return new ConfigurationResult(false,
                        "Sink creation command succeeded but device not found",
                        sinkName, commands);
            }

        } catch (Exception e) {
            return new ConfigurationResult(false,
                    "Error executing pactl: " + e.getMessage(),
                    sinkName, commands);
        }
    }

    /**
     * Gets the commands needed to configure virtual audio on Linux.
     * Does not execute them - just returns the commands.
     *
     * @return list of shell commands
     */
    public List<String> getLinuxConfigurationCommands() {
        List<String> commands = new ArrayList<>();
        String sinkName = "ftx1_audio";

        commands.add("# Remove existing sink if present");
        commands.add("pactl unload-module module-null-sink 2>/dev/null || true");
        commands.add("");
        commands.add("# Create null sink with correct sample rate");
        commands.add(String.format(
                "pactl load-module module-null-sink sink_name=%s " +
                "sink_properties=device.description=FTX1_Virtual_Audio " +
                "rate=%d channels=%d format=s%dle",
                sinkName, config.getSampleRate(), config.getChannels(), config.getBitsPerSample()));
        commands.add("");
        commands.add("# Create loopback for bidirectional audio");
        commands.add(String.format(
                "pactl load-module module-loopback source=%s.monitor sink=%s latency_msec=20",
                sinkName, sinkName));
        commands.add("");
        commands.add("# Verify creation");
        commands.add("pactl list short sinks | grep " + sinkName);

        return commands;
    }

    /**
     * Gets the commands needed to make Linux configuration persistent.
     *
     * @return shell commands to add to pulse config
     */
    public String getLinuxPersistentConfig() {
        String sinkName = "ftx1_audio";
        return String.format("""
            # Add these lines to ~/.config/pulse/default.pa or /etc/pulse/default.pa
            # Then restart PulseAudio: pulseaudio -k && pulseaudio --start

            # FTX1-Hamlib Virtual Audio Device
            load-module module-null-sink sink_name=%s rate=%d channels=%d format=s%dle sink_properties=device.description=FTX1_Virtual_Audio
            load-module module-loopback source=%s.monitor sink=%s latency_msec=20
            """, sinkName, config.getSampleRate(), config.getChannels(), config.getBitsPerSample(),
                sinkName, sinkName);
    }

    // ========== macOS Configuration Helpers ==========

    /**
     * Gets the commands to check BlackHole configuration on macOS.
     *
     * @return shell commands for diagnostics
     */
    public List<String> getMacOSDiagnosticCommands() {
        List<String> commands = new ArrayList<>();

        commands.add("# Check if BlackHole is installed");
        commands.add("system_profiler SPAudioDataType 2>/dev/null | grep -A 5 -i blackhole");
        commands.add("");
        commands.add("# List all audio devices with sample rates");
        commands.add("system_profiler SPAudioDataType");
        commands.add("");
        commands.add("# Check Audio MIDI Setup (opens GUI)");
        commands.add("# open /Applications/Utilities/Audio\\ MIDI\\ Setup.app");

        return commands;
    }

    /**
     * Checks if BlackHole is installed on macOS.
     *
     * @return true if BlackHole appears to be installed
     */
    public boolean isMacOSBlackHoleInstalled() {
        if (platform != Platform.MACOS) {
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                    "system_profiler SPAudioDataType 2>/dev/null | grep -i blackhole");
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();
            return line != null && line.toLowerCase().contains("blackhole");
        } catch (Exception e) {
            return false;
        }
    }

    // ========== Windows Configuration Helpers ==========

    /**
     * Gets instructions for Windows PowerShell to check VB-Cable configuration.
     *
     * @return PowerShell commands
     */
    public List<String> getWindowsDiagnosticCommands() {
        List<String> commands = new ArrayList<>();

        commands.add("# PowerShell commands to check audio devices");
        commands.add("Get-WmiObject Win32_SoundDevice | Select-Object Name, Status");
        commands.add("");
        commands.add("# Check for VB-Cable specifically");
        commands.add("Get-WmiObject Win32_SoundDevice | Where-Object { $_.Name -like '*VB-Audio*' -or $_.Name -like '*CABLE*' }");
        commands.add("");
        commands.add("# Open Sound settings (run in PowerShell)");
        commands.add("# Start-Process mmsys.cpl");

        return commands;
    }

    // ========== Enhanced Diagnostic Report ==========

    /**
     * Generates an enhanced diagnostic report with verification results.
     */
    public String generateEnhancedDiagnosticReport() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║         Virtual Audio Diagnostic Report                      ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");

        sb.append("Platform: ").append(platform).append("\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(" ")
          .append(System.getProperty("os.version")).append("\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("Required Format: ").append(config.getSampleRate()).append(" Hz, ")
          .append(config.getBitsPerSample()).append("-bit, ")
          .append(config.getChannels()).append(" channel(s)\n\n");

        // Capture devices
        sb.append("┌─────────────────────────────────────────────────────────────┐\n");
        sb.append("│ CAPTURE DEVICES                                             │\n");
        sb.append("└─────────────────────────────────────────────────────────────┘\n");

        List<AudioDeviceInfo> captureDevices = deviceManager.discoverCaptureDevices();
        AudioDeviceInfo bestCapture = findBestCaptureDevice();

        for (AudioDeviceInfo device : captureDevices) {
            String marker = device.equals(bestCapture) ? " ★ RECOMMENDED" : "";
            String virtual = device.isVirtual() ? " [VIRTUAL]" : "";
            sb.append("  • ").append(device.getName()).append(virtual).append(marker).append("\n");

            if (device.isVirtual()) {
                VerificationResult result = verifyDeviceConfiguration(device, true);
                if (!result.isSuccess()) {
                    for (String issue : result.getIssues()) {
                        sb.append("      ⚠ ").append(issue).append("\n");
                    }
                }
            }
        }

        // Playback devices
        sb.append("\n┌─────────────────────────────────────────────────────────────┐\n");
        sb.append("│ PLAYBACK DEVICES                                            │\n");
        sb.append("└─────────────────────────────────────────────────────────────┘\n");

        List<AudioDeviceInfo> playbackDevices = deviceManager.discoverPlaybackDevices();
        AudioDeviceInfo bestPlayback = findBestPlaybackDevice();

        for (AudioDeviceInfo device : playbackDevices) {
            String marker = device.equals(bestPlayback) ? " ★ RECOMMENDED" : "";
            String virtual = device.isVirtual() ? " [VIRTUAL]" : "";
            sb.append("  • ").append(device.getName()).append(virtual).append(marker).append("\n");

            if (device.isVirtual()) {
                VerificationResult result = verifyDeviceConfiguration(device, false);
                if (!result.isSuccess()) {
                    for (String issue : result.getIssues()) {
                        sb.append("      ⚠ ").append(issue).append("\n");
                    }
                }
            }
        }

        // Status summary
        sb.append("\n┌─────────────────────────────────────────────────────────────┐\n");
        sb.append("│ STATUS                                                      │\n");
        sb.append("└─────────────────────────────────────────────────────────────┘\n");

        boolean virtualAvailable = isVirtualAudioAvailable();
        sb.append("  Virtual Audio Available: ").append(virtualAvailable ? "✓ YES" : "✗ NO").append("\n");

        if (bestCapture != null) {
            VerificationResult captureResult = verifyDeviceConfiguration(bestCapture, true);
            sb.append("  Capture Device Ready: ")
              .append(captureResult.isSuccess() ? "✓ YES" : "⚠ NEEDS CONFIGURATION").append("\n");
        }

        if (bestPlayback != null) {
            VerificationResult playbackResult = verifyDeviceConfiguration(bestPlayback, false);
            sb.append("  Playback Device Ready: ")
              .append(playbackResult.isSuccess() ? "✓ YES" : "⚠ NEEDS CONFIGURATION").append("\n");
        }

        // Platform-specific notes
        if (!virtualAvailable) {
            sb.append("\n").append(getInstallInstructions());
        } else if (bestCapture != null || bestPlayback != null) {
            VerificationResult result = bestCapture != null ?
                    verifyDeviceConfiguration(bestCapture, true) :
                    verifyDeviceConfiguration(bestPlayback, false);

            if (!result.isSuccess() && !result.getSuggestions().isEmpty()) {
                sb.append("\n┌─────────────────────────────────────────────────────────────┐\n");
                sb.append("│ CONFIGURATION NEEDED                                        │\n");
                sb.append("└─────────────────────────────────────────────────────────────┘\n");
                for (String suggestion : result.getSuggestions()) {
                    sb.append("  → ").append(suggestion).append("\n");
                }
            }
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
