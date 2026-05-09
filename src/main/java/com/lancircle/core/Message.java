package com.lancircle.core;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A single unit of communication in LAN Circle.
 * Can represent plain text, a file notification, an image, or a system event.
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    // Core fields
    private final String id;
    private final String senderUsername;
    private final String senderIp;
    private final List<String> recipientIps;
    private final String content;
    private final Type type;
    private final LocalDateTime timestamp;
    // Derived
    private final boolean groupMessage;
    // File-transfer metadata (TYPE == FILE | IMAGE)
    private String fileName;
    private long fileSize;
    private String localSavePath;   // filled in by receiver after transfer

    public Message(String id,
                   String senderUsername,
                   String senderIp,
                   List<String> recipientIps,
                   String content,
                   Type type) {
        this.id = id;
        this.senderUsername = senderUsername;
        this.senderIp = senderIp;
        this.recipientIps = recipientIps != null ? recipientIps : new ArrayList<>();
        this.content = content;
        this.type = type;
        this.timestamp = LocalDateTime.now();
        this.groupMessage = this.recipientIps.size() > 1;
    }

    public String getId() {
        return id;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getSenderUsername() {
        return senderUsername;
    }

    public String getSenderIp() {
        return senderIp;
    }

    public List<String> getRecipientIps() {
        return recipientIps;
    }

    public String getContent() {
        return content;
    }

    public Type getType() {
        return type;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String n) {
        this.fileName = n;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long s) {
        this.fileSize = s;
    }

    public String getLocalSavePath() {
        return localSavePath;
    }

    // ── Setters (file metadata) ───────────────────────────────────────────────

    public void setLocalSavePath(String p) {
        this.localSavePath = p;
    }

    public boolean isGroupMessage() {
        return groupMessage;
    }

    public String getFormattedTime() {
        return timestamp.format(FMT);
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    public String getFormattedSize() {
        if (fileSize <= 0) return "";
        if (fileSize < 1_024) return fileSize + " B";
        if (fileSize < 1_048_576) return String.format("%.1f KB", fileSize / 1_024.0);
        if (fileSize < 1_073_741_824L) return String.format("%.1f MB", fileSize / 1_048_576.0);
        return String.format("%.2f GB", fileSize / 1_073_741_824.0);
    }

    public enum Type {TEXT, FILE, IMAGE, SYSTEM, GROUP}
}