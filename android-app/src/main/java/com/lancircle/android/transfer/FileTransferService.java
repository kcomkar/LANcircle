package com.lancircle.android.transfer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.lancircle.android.core.MessageListener;
import com.lancircle.android.util.NetworkUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FileTransferService {
    private static final int BUF = 65_536;
    private static final String FILE_MAGIC = "LCF2";

    private final Context context;
    private final File downloadDir;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    public FileTransferService(Context context) {
        this.context = context.getApplicationContext();
        File base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        this.downloadDir = base == null ? new File(context.getFilesDir(), "downloads") : base;
        this.downloadDir.mkdirs();
    }

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    public File getDownloadDir() {
        return downloadDir;
    }

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(NetworkUtil.FILE_PORT);
        serverSocket.setSoTimeout(1_000);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "android-file-xfer");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::acceptLoop);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (executor != null) executor.shutdownNow();
    }

    public void sendUri(Uri uri, String displayName, long size, List<String> ips) {
        sendUri(uri, displayName, displayName, size, ips);
    }

    public void sendUri(Uri uri, String remoteName, String displayName, long size, List<String> ips) {
        for (String ip : ips) {
            String tid = NetworkUtil.generateId();
            executor.submit(() -> doSend(uri, remoteName, displayName, size, ip, tid));
        }
    }

    public String displayName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    public long size(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setReceiveBufferSize(BUF);
                executor.submit(() -> receiveFile(client));
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) fireError("File accept failed: " + e.getMessage());
            }
        }
    }

    private void receiveFile(Socket socket) {
        String tid = NetworkUtil.generateId();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), BUF))) {
            byte[] magic = readBytes(in, FILE_MAGIC.length());
            if (!FILE_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
                throw new IOException("Unsupported file transfer");
            }

            long fileSize = in.readLong();
            int metadataLen = in.readInt();
            String fileName = new String(readBytes(in, metadataLen), StandardCharsets.UTF_8);

            listeners.forEach(l -> l.onFileTransferProgress(tid, fileName, 0, fileSize));
            File dest = uniqueDest(fileName);
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();

            try (FileOutputStream fos = new FileOutputStream(dest);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, BUF)) {
                byte[] buf = new byte[BUF];
                long received = 0;
                int read;
                while ((read = in.read(buf)) >= 0) {
                    bos.write(buf, 0, read);
                    received += read;
                    long progress = received;
                    listeners.forEach(l -> l.onFileTransferProgress(tid, fileName, progress, fileSize));
                }
            }
            listeners.forEach(l -> l.onFileTransferComplete(tid, fileName, dest.getAbsolutePath()));
        } catch (Exception e) {
            listeners.forEach(l -> l.onFileTransferFailed(tid, "?", e.getMessage()));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void doSend(Uri uri, String remoteName, String displayName, long size, String ip, String tid) {
        listeners.forEach(l -> l.onFileTransferProgress(tid, displayName, 0, size));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, NetworkUtil.FILE_PORT), 5_000);
            socket.setSendBufferSize(BUF);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), BUF));

            byte[] metadata = remoteName.getBytes(StandardCharsets.UTF_8);

            out.write(FILE_MAGIC.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(size);
            out.writeInt(metadata.length);
            out.write(metadata);

            try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("Could not open file");
                try (BufferedInputStream bis = new BufferedInputStream(raw, BUF)) {
                    byte[] buf = new byte[BUF];
                    long sent = 0;
                    int read;
                    while ((read = bis.read(buf)) > 0) {
                        out.write(buf, 0, read);
                        sent += read;
                        long progress = sent;
                        listeners.forEach(l -> l.onFileTransferProgress(tid, displayName, progress, size));
                    }
                }
            }
            out.flush();
            listeners.forEach(l -> l.onFileTransferComplete(tid, displayName, ip));
        } catch (Exception e) {
            listeners.forEach(l -> l.onFileTransferFailed(tid, displayName, e.getMessage()));
        }
    }

    private File uniqueDest(String fileName) {
        File dest = new File(downloadDir, fileName.replace("/", File.separator));
        if (!dest.exists()) return dest;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        int n = 1;
        while (dest.exists()) dest = new File(downloadDir, base + " (" + n++ + ")" + ext);
        return dest;
    }

    private void fireError(String message) {
        listeners.forEach(l -> l.onError(message));
    }

    private static byte[] readBytes(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
