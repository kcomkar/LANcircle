package com.lancircle.network;

import com.lancircle.core.Message;
import com.lancircle.core.MessageListener;
import com.lancircle.util.JsonUtil;
import com.lancircle.util.NetworkUtil;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * TCP-based reliable text / control message delivery.
 *
 * <p>Each outbound message opens a fresh short-lived TCP connection to the
 * target peer on {@link NetworkUtil#MESSAGE_PORT}.  The server side accepts
 * connections on the same port and dispatches each payload to all registered
 * {@link MessageListener}s.</p>
 */
public class MessageService {
    private static final Logger LOG = Logger.getLogger(MessageService.class.getName());

    private final String username;
    private final String localIp;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    public MessageService(String username, String localIp) {
        this.username = username;
        this.localIp = localIp;
    }

    public void addListener(MessageListener l) {
        listeners.add(l);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(NetworkUtil.MESSAGE_PORT);
        serverSocket.setSoTimeout(1_000);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "msg-worker");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::acceptLoop);
        LOG.info("MessageService listening on port " + NetworkUtil.MESSAGE_PORT);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (executor != null) executor.shutdownNow();
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleIncoming(client));
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) LOG.warning("Accept error: " + e.getMessage());
            }
        }
    }

    private void handleIncoming(Socket socket) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseAndDispatch(sb.toString());
        } catch (Exception e) {
            LOG.warning("Read error: " + e.getMessage());
            listeners.forEach(l -> l.onError("Could not read incoming message."));
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void parseAndDispatch(String json) {
        if (json == null || json.isBlank()) return;
        try {
            Map<String, String> o = JsonUtil.parseObject(json);
            String id = o.getOrDefault("id", NetworkUtil.generateId());
            String senderName = o.get("senderUsername");
            String senderIp = o.get("senderIp");
            String content = o.getOrDefault("content", "");
            Message.Type type = Message.Type.valueOf(o.getOrDefault("type", "TEXT"));

            List<String> recipients = JsonUtil.parseStringArray(o.get("recipients"));

            Message msg = new Message(id, senderName, senderIp, recipients, content, type);
            if (type == Message.Type.FILE || type == Message.Type.IMAGE) {
                msg.setFileName(o.getOrDefault("fileName", "file"));
                msg.setFileSize(Long.parseLong(o.getOrDefault("fileSize", "0")));
            }
            listeners.forEach(l -> l.onMessageReceived(msg));
        } catch (Exception e) {
            LOG.warning("Parse error: " + e.getMessage());
        }
    }

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Sends {@code message} to every IP in {@code recipientIps}.
     * Returns {@code true} only if all deliveries succeeded.
     */
    public boolean sendMessage(Message message, List<String> recipientIps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.getId());
        payload.put("senderUsername", message.getSenderUsername());
        payload.put("senderIp", message.getSenderIp());
        payload.put("content", message.getContent());
        payload.put("type", message.getType().name());
        payload.put("ts", System.currentTimeMillis());
        payload.put("recipients", recipientIps);

        if (message.getType() == Message.Type.FILE
                || message.getType() == Message.Type.IMAGE) {
            payload.put("fileName", message.getFileName());
            payload.put("fileSize", message.getFileSize());
        }

        String raw = JsonUtil.object(payload);
        boolean allOk = true;
        for (String ip : recipientIps) allOk &= sendRaw(ip, raw);
        return allOk;
    }

    private boolean sendRaw(String ip, String json) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, NetworkUtil.MESSAGE_PORT), 3_000);
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            pw.println(json);
            return true;
        } catch (Exception e) {
            LOG.warning("Delivery failed to " + ip + ": " + e.getMessage());
            listeners.forEach(l -> l.onError("Message delivery failed to " + ip));
            return false;
        }
    }
}
