package com.lancircle.core;

/**
 * Observer interface that all UI and service components implement
 * to receive LAN Circle events.
 */
public interface MessageListener {

    /**
     * A text / file / image / system message has arrived (or been echoed locally).
     */
    void onMessageReceived(Message message);

    /**
     * A new peer has been discovered on the LAN.
     */
    void onUserJoined(User user);

    /**
     * A peer has gone offline or timed out.
     */
    void onUserLeft(User user);

    /**
     * A peer changed their status (ONLINE ↔ AWAY ↔ BUSY).
     */
    void onUserStatusChanged(User user);

    /**
     * Periodic progress callback during a file transfer.
     *
     * @param transferId  unique transfer session ID
     * @param fileName    name of the file being transferred
     * @param transferred bytes transferred so far
     * @param total       total file size in bytes
     */
    void onFileTransferProgress(String transferId, String fileName,
                                long transferred, long total);

    /**
     * A file transfer has completed successfully.
     */
    void onFileTransferComplete(String transferId, String fileName, String savePath);

    /**
     * A file transfer has failed.
     */
    void onFileTransferFailed(String transferId, String fileName, String reason);

    /**
     * A generic error occurred (shown as a status-bar message in the UI).
     */
    void onError(String error);
}