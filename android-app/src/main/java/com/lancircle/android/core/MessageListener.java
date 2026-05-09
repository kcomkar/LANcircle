package com.lancircle.android.core;

public interface MessageListener {
    void onMessageReceived(Message message);
    void onUserJoined(User user);
    void onUserLeft(User user);
    void onUserStatusChanged(User user);
    void onFileTransferProgress(String transferId, String fileName, long transferred, long total);
    void onFileTransferComplete(String transferId, String fileName, String savePath);
    void onFileTransferFailed(String transferId, String fileName, String reason);
    void onError(String error);
}
