package com.lancircle.android.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class Message {
    public enum Type { TEXT, FILE, IMAGE, SYSTEM, GROUP }

    private final String id;
    private final String senderUsername;
    private final String senderIp;
    private final List<String> recipientIps;
    private final String content;
    private final Type type;
    private final LocalDateTime timestamp;
    private String fileName;
    private long fileSize;

    public Message(String id, String senderUsername, String senderIp, List<String> recipientIps,
                   String content, Type type) {
        this.id = id;
        this.senderUsername = senderUsername;
        this.senderIp = senderIp;
        this.recipientIps = recipientIps == null ? new ArrayList<>() : recipientIps;
        this.content = content == null ? "" : content;
        this.type = type;
        this.timestamp = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getSenderUsername() { return senderUsername; }
    public String getSenderIp() { return senderIp; }
    public List<String> getRecipientIps() { return recipientIps; }
    public String getContent() { return content; }
    public Type getType() { return type; }
    public String getFileName() { return fileName; }
    public long getFileSize() { return fileSize; }
    public boolean isGroupMessage() { return recipientIps.size() > 1; }

    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getFormattedTime() {
        return timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public String getFormattedSize() {
        if (fileSize <= 0) return "";
        if (fileSize < 1_024) return fileSize + " B";
        if (fileSize < 1_048_576) return String.format("%.1f KB", fileSize / 1_024.0);
        if (fileSize < 1_073_741_824L) return String.format("%.1f MB", fileSize / 1_048_576.0);
        return String.format("%.2f GB", fileSize / 1_073_741_824.0);
    }
}
