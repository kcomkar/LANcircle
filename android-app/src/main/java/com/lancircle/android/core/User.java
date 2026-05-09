package com.lancircle.android.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class User {
    public enum Status { ONLINE, AWAY, BUSY, OFFLINE }

    private final String username;
    private final String ipAddress;
    private Status status;
    private LocalDateTime lastSeen;

    public User(String username, String ipAddress) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.status = Status.ONLINE;
        this.lastSeen = LocalDateTime.now();
    }

    public String getUsername() { return username; }
    public String getIpAddress() { return ipAddress; }
    public Status getStatus() { return status; }

    public void setStatus(Status status) {
        this.status = status;
        this.lastSeen = LocalDateTime.now();
    }

    public String getFormattedLastSeen() {
        return lastSeen.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @Override public boolean equals(Object o) {
        return o instanceof User && ipAddress.equals(((User) o).ipAddress);
    }

    @Override public int hashCode() {
        return ipAddress.hashCode();
    }

    @Override public String toString() {
        return username + "  " + ipAddress + "  " + status;
    }
}
