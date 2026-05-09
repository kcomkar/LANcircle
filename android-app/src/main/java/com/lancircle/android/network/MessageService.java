package com.lancircle.android.network;

import com.lancircle.android.core.Message;
import com.lancircle.android.core.MessageListener;
import com.lancircle.android.util.JsonUtil;
import com.lancircle.android.util.NetworkUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

public final class MessageService {
    private final String username;
    private final String localIp;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    public MessageService(String username, String localIp) {
        this.username = username;
        this.localIp = localIp;
    }

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    public void start() throws IOException {
        running = true;
        serverSocket = new ServerSocket(NetworkUtil.MESSAGE_PORT);
        serverSocket.setSoTimeout(1_000);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "android-message");
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

    public boolean sendMessage(Message message, List<String> recipientIps) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", message.getId());
        payload.put("senderUsername", message.getSenderUsername());
        payload.put("senderIp", message.getSenderIp());
        payload.put("content", message.getContent());
        payload.put("type", message.getType().name());
        payload.put("ts", System.currentTimeMillis());
        payload.put("recipients", recipientIps);
        if (message.getType() == Message.Type.FILE || message.getType() == Message.Type.IMAGE) {
            payload.put("fileName", message.getFileName());
            payload.put("fileSize", message.getFileSize());
        }

        String raw = JsonUtil.object(payload);
        boolean ok = true;
        for (String ip : recipientIps) ok &= sendRaw(ip, raw);
        return ok;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleIncoming(client));
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                if (running) fireError("Message accept failed: " + e.getMessage());
            }
        }
    }

    private void handleIncoming(Socket socket) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            parseAndDispatch(sb.toString());
        } catch (Exception e) {
            fireError("Could not read incoming message.");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void parseAndDispatch(String json) {
        try {
            Map<String, String> o = JsonUtil.parseObject(json);
            Message.Type type = Message.Type.valueOf(o.getOrDefault("type", "TEXT"));
            Message msg = new Message(o.getOrDefault("id", NetworkUtil.generateId()),
                    o.get("senderUsername"), o.get("senderIp"),
                    JsonUtil.parseStringArray(o.get("recipients")),
                    o.getOrDefault("content", ""), type);
            if (type == Message.Type.FILE || type == Message.Type.IMAGE) {
                msg.setFileName(o.getOrDefault("fileName", "file"));
                msg.setFileSize(Long.parseLong(o.getOrDefault("fileSize", "0")));
            }
            listeners.forEach(l -> l.onMessageReceived(msg));
        } catch (Exception e) {
            fireError("Message parse failed: " + e.getMessage());
        }
    }

    private boolean sendRaw(String ip, String json) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, NetworkUtil.MESSAGE_PORT), 3_000);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),
                    StandardCharsets.UTF_8), true);
            pw.println(json);
            return true;
        } catch (Exception e) {
            fireError("Message delivery failed to " + ip);
            return false;
        }
    }

    private void fireError(String message) {
        listeners.forEach(l -> l.onError(message));
    }
}
