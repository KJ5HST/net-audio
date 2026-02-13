/*
 * Copyright (c) 2025-2026 Terrell Deppe (KJ5HST). All rights reserved.
 * This software is proprietary and confidential.
 * Unauthorized use is strictly prohibited.
 */
package com.yaesu.audio;

import javax.sound.sampled.Mixer;

/**
 * Information about an audio device.
 * <p>
 * Wraps javax.sound.sampled.Mixer.Info with additional metadata
 * for device type classification and display purposes.
 * </p>
 */
public class AudioDeviceInfo {

    /**
     * Type of audio device.
     */
    public enum DeviceType {
        /** Physical hardware device */
        HARDWARE,
        /** Virtual audio device (VB-Cable, BlackHole, etc.) */
        VIRTUAL,
        /** Unknown device type */
        UNKNOWN
    }

    /**
     * Capability of the device.
     */
    public enum Capability {
        /** Device can capture audio (microphone, line-in) */
        CAPTURE,
        /** Device can play audio (speakers, line-out) */
        PLAYBACK,
        /** Device can both capture and play */
        DUPLEX
    }

    private final Mixer.Info mixerInfo;
    private final DeviceType deviceType;
    private final Capability capability;
    private final String displayName;

    /**
     * Creates a new AudioDeviceInfo.
     *
     * @param mixerInfo the underlying mixer info
     * @param deviceType the type of device
     * @param capability the device capability
     */
    public AudioDeviceInfo(Mixer.Info mixerInfo, DeviceType deviceType, Capability capability) {
        this.mixerInfo = mixerInfo;
        this.deviceType = deviceType;
        this.capability = capability;
        this.displayName = createDisplayName(mixerInfo);
    }

    /**
     * Creates a new AudioDeviceInfo with default unknown type.
     *
     * @param mixerInfo the underlying mixer info
     * @param capability the device capability
     */
    public AudioDeviceInfo(Mixer.Info mixerInfo, Capability capability) {
        this(mixerInfo, DeviceType.UNKNOWN, capability);
    }

    private String createDisplayName(Mixer.Info info) {
        String name = info.getName();
        String vendor = info.getVendor();

        // Clean up common prefixes/suffixes
        if (name.startsWith("Port ")) {
            name = name.substring(5);
        }

        // Add vendor if meaningful
        if (vendor != null && !vendor.isEmpty() && !vendor.equals("Unknown Vendor")) {
            return name + " (" + vendor + ")";
        }

        return name;
    }

    /**
     * Gets the underlying Mixer.Info.
     */
    public Mixer.Info getMixerInfo() {
        return mixerInfo;
    }

    /**
     * Gets the device type.
     */
    public DeviceType getDeviceType() {
        return deviceType;
    }

    /**
     * Gets the device capability.
     */
    public Capability getCapability() {
        return capability;
    }

    /**
     * Gets the device name from the mixer info.
     */
    public String getName() {
        return mixerInfo.getName();
    }

    /**
     * Gets the device description from the mixer info.
     */
    public String getDescription() {
        return mixerInfo.getDescription();
    }

    /**
     * Gets a user-friendly display name.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Checks if this is a virtual audio device.
     */
    public boolean isVirtual() {
        return deviceType == DeviceType.VIRTUAL;
    }

    /**
     * Checks if this device supports capture.
     */
    public boolean supportsCapture() {
        return capability == Capability.CAPTURE || capability == Capability.DUPLEX;
    }

    /**
     * Checks if this device supports playback.
     */
    public boolean supportsPlayback() {
        return capability == Capability.PLAYBACK || capability == Capability.DUPLEX;
    }

    @Override
    public String toString() {
        return displayName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AudioDeviceInfo other = (AudioDeviceInfo) obj;
        return mixerInfo.equals(other.mixerInfo);
    }

    @Override
    public int hashCode() {
        return mixerInfo.hashCode();
    }
}
