package com.lancircle.transfer;

import com.lancircle.core.MessageListener;
import com.lancircle.util.NetworkUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * High-speed TCP file transfer.
 *
 * <h3>Wire protocol (per file)</h3>
 * <pre>
 *   [magic       : 4 bytes "LCF2"]
 *   [fileSize    : 8 bytes]
 *   [metadataLen : 4 bytes]
 *   [metadata    : metadataLen bytes UTF-8 filename/path]
 *   [fileBytes   : raw bytes until EOF]
 * </pre>
 * <p>
 * Folders are walked recursively; each file is transferred as a separate
 * connection using relative path as the filename so the receiver can
 * reconstruct the directory tree.
 */
public class FileTransferService {
    private static final Logger LOG = Logger.getLogger(FileTransferService.class.getName());
    private static final int BUF = 65_536;   // 64 KB chunks
    private static final String FILE_MAGIC = "LCF2";

    private final String downloadDir;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public FileTransferService() {
        this.downloadDir = System.getProperty("user.home")
                + File.separator + "LANCircle Downloads";
        new File(downloadDir).mkdirs();
    }

    public void addListener(MessageListener l) {
        listeners.add(l);
    }

    public String getDownloadDir() {
        return downloadDir;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(NetworkUtil.FILE_PORT);
        serverSocket.setSoTimeout(1_000);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "file-xfer");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::acceptLoop);
        LOG.info("FileTransferService listening on port " + NetworkUtil.FILE_PORT);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (executor != null) executor.shutdownNow();
    }

    // ── Receive side ──────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setReceiveBufferSize(BUF);
                executor.submit(() -> receiveFile(client));
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) LOG.warning("Accept error: " + e.getMessage());
            }
        }
    }

    private void receiveFile(Socket socket) {
        String tid = NetworkUtil.generateId();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), BUF))) {

            byte[] magic = new byte[FILE_MAGIC.length()];
            in.readFully(magic);
            String magicText = new String(magic, StandardCharsets.US_ASCII);
            if (!FILE_MAGIC.equals(magicText)) {
                throw new IOException("Unsupported file transfer");
            }

            long fileSize = in.readLong();
            int metadataLen = in.readInt();
            String fileName = new String(readBytes(in, metadataLen), StandardCharsets.UTF_8);

            fire(l -> l.onFileTransferProgress(tid, fileName, 0, fileSize));

            File dest = uniqueDest(fileName);
            // Ensure subdirectory exists (for folder transfers)
            dest.getParentFile().mkdirs();

            try (FileOutputStream fos = new FileOutputStream(dest);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, BUF)) {
                byte[] buf = new byte[BUF];
                long received = 0;
                int read;
                while ((read = in.read(buf)) >= 0) {
                    bos.write(buf, 0, read);
                    received += read;
                    final long r = received;
                    fire(l -> l.onFileTransferProgress(tid, fileName, r, fileSize));
                }
            }

            final String path = dest.getAbsolutePath();
            fire(l -> l.onFileTransferComplete(tid, fileName, path));
            LOG.info("Received: " + fileName + " → " + path);

        } catch (Exception e) {
            LOG.warning("Receive error: " + e.getMessage());
            fire(l -> l.onFileTransferFailed(tid, "?", e.getMessage()));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ── Send side ─────────────────────────────────────────────────────────────

    /**
     * Send a single file to each target IP (non-blocking).
     */
    public void sendFile(File file, List<String> ips) {
        sendFile(file, file.getName(), ips);
    }

    /**
     * Internal variant that lets us supply a relative path for folder transfers.
     */
    private void sendFile(File file, String relativeName, List<String> ips) {
        for (String ip : ips) {
            String tid = NetworkUtil.generateId();
            executor.submit(() -> doSend(file, relativeName, ip, tid));
        }
    }

    /**
     * Walk a folder and send every file, preserving relative paths
     * so the receiver can reconstruct the tree.
     */
    public void sendFolder(File folder, List<String> ips) {
        Path base = folder.getParentFile().toPath();
        try {
            Files.walk(folder.toPath())
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> sendFile(p.toFile(),
                            base.relativize(p).toString().replace(File.separator, "/"), ips));
        } catch (IOException e) {
            fire(l -> l.onError("Folder read error: " + e.getMessage()));
        }
    }

    private void doSend(File file, String remoteName, String ip, String tid) {
        long size = file.length();
        fire(l -> l.onFileTransferProgress(tid, file.getName(), 0, size));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, NetworkUtil.FILE_PORT), 5_000);
            s.setSendBufferSize(BUF);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(s.getOutputStream(), BUF));

            byte[] metadata = remoteName.getBytes(StandardCharsets.UTF_8);

            out.write(FILE_MAGIC.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(size);
            out.writeInt(metadata.length);
            out.write(metadata);

            try (FileInputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis, BUF)) {
                byte[] buf = new byte[BUF];
                long sent = 0;
                int read;
                while ((read = bis.read(buf)) > 0) {
                    out.write(buf, 0, read);
                    sent += read;
                    final long s2 = sent;
                    fire(l -> l.onFileTransferProgress(tid, file.getName(), s2, size));
                }
            }
            out.flush();
            fire(l -> l.onFileTransferComplete(tid, file.getName(), ip));
            LOG.info("Sent: " + file.getName() + " → " + ip);

        } catch (Exception e) {
            LOG.warning("Send error to " + ip + ": " + e.getMessage());
            fire(l -> l.onFileTransferFailed(tid, file.getName(), e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Avoid overwriting an existing file by appending (1), (2), …
     */
    private File uniqueDest(String fileName) {
        // Handle sub-paths from folder transfers
        File dest = new File(downloadDir, fileName.replace("/", File.separator));
        if (!dest.exists()) return dest;

        String base = fileName, ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        int n = 1;
        while (dest.exists())
            dest = new File(downloadDir, base + " (" + n++ + ")" + ext);
        return dest;
    }

    private void fire(LA a) {
        listeners.forEach(a::run);
    }

    private static byte[] readBytes(DataInputStream in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    @FunctionalInterface
    interface LA {
        void run(MessageListener l);
    }
}
