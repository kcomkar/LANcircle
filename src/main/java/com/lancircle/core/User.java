package com.lancircle.core;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a peer discovered on the LAN.
 */
public class User implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String username;
    private final String ipAddress;
    private Status status;
    private LocalDateTime lastSeen;
    private String statusMessage;
    public User(String username, String ipAddress) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.status = Status.ONLINE;
        this.lastSeen = LocalDateTime.now();
        this.statusMessage = "";
    }

    public String getUsername() {
        return username;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getIpAddress() {
        return ipAddress;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status s) {
        this.status = s;
        this.lastSeen = LocalDateTime.now();
    }

    public LocalDateTime getLastSeen() {
        return lastSeen;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setStatusMessage(String msg) {
        this.statusMessage = msg;
    }

    public boolean isOnline() {
        return status == Status.ONLINE || status == Status.BUSY;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a coloured dot character matching the current status.
     */
    public String getStatusDot() {
        return switch (status) {
            case ONLINE -> "●";   // green (painted by renderer)
            case AWAY -> "●";   // amber
            case BUSY -> "●";   // red
            case OFFLINE -> "○";   // grey
        };
    }

    public String getFormattedLastSeen() {
        return lastSeen.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof User)) return false;
        return ipAddress.equals(((User) o).ipAddress);
    }

    @Override
    public int hashCode() {
        return ipAddress.hashCode();
    }

    @Override
    public String toString() {
        return username + "  [" + ipAddress + "]  " + status;
    }

    public enum Status {ONLINE, AWAY, BUSY, OFFLINE}
}