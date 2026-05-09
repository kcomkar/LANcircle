package com.lancircle.android.network;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.lancircle.android.core.MessageListener;
import com.lancircle.android.core.User;
import com.lancircle.android.util.JsonUtil;
import com.lancircle.android.util.NetworkUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscoveryService {
    private static final int BUF = 1_024;

    private final Context context;
    private final String username;
    private final String localIp;
    private final Map<String, User> knownUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenTimes = new ConcurrentHashMap<>();
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private DatagramSocket txSocket;
    private DatagramSocket rxSocket;
    private ScheduledExecutorService scheduler;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean running;
    private volatile User.Status myStatus = User.Status.ONLINE;

    public DiscoveryService(Context context, String username, String localIp) {
        this.context = context.getApplicationContext();
        this.username = username;
        this.localIp = localIp;
    }

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    public void start() throws SocketException {
        running = true;
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("lan-circle-discovery");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        txSocket = new DatagramSocket();
        txSocket.setBroadcast(true);
        rxSocket = new DatagramSocket(NetworkUtil.DISCOVERY_PORT);
        rxSocket.setBroadcast(true);
        rxSocket.setSoTimeout(500);

        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "android-discovery");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::broadcast, 0, NetworkUtil.DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::receive, 150, 300, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::reapTimedOut, 5_000, 5_000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        sendLeave();
        if (scheduler != null) scheduler.shutdownNow();
        if (txSocket != null) txSocket.close();
        if (rxSocket != null) rxSocket.close();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
    }

    public void setStatus(User.Status status) {
        myStatus = status;
        broadcast();
    }

    public Map<String, User> getKnownUsers() {
        return Collections.unmodifiableMap(knownUsers);
    }

    private void broadcast() {
        send(buildPayload("PRESENCE"));
    }

    private void sendLeave() {
        send(buildPayload("LEAVE"));
    }

    private String buildPayload(String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("username", username);
        payload.put("ip", localIp);
        payload.put("status", myStatus.name());
        payload.put("ts", System.currentTimeMillis());
        return JsonUtil.object(payload);
    }

    private void send(String payload) {
        try {
            if (txSocket == null || txSocket.isClosed()) return;
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pkt = new DatagramPacket(data, data.length,
                    InetAddress.getByName(NetworkUtil.getBroadcastAddress(context)), NetworkUtil.DISCOVERY_PORT);
            txSocket.send(pkt);
        } catch (Exception e) {
            if (running) fireError("Discovery broadcast failed: " + e.getMessage());
        }
    }

    private void receive() {
        if (!running || rxSocket == null || rxSocket.isClosed()) return;
        try {
            byte[] buf = new byte[BUF];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            rxSocket.receive(pkt);
            Map<String, String> obj = JsonUtil.parseObject(new String(pkt.getData(), 0, pkt.getLength(),
                    StandardCharsets.UTF_8));
            String type = obj.get("type");
            String peerIp = obj.get("ip");
            String peerName = obj.get("username");
            if (peerIp == null || peerIp.equals(localIp)) return;
            if ("PRESENCE".equals(type)) {
                handlePresence(peerIp, peerName, User.Status.valueOf(obj.getOrDefault("status", "ONLINE")));
            } else if ("LEAVE".equals(type)) {
                handleLeave(peerIp);
            }
        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
            if (running) fireError("Discovery receive failed: " + e.getMessage());
        }
    }

    private void handlePresence(String ip, String name, User.Status status) {
        lastSeenTimes.put(ip, System.currentTimeMillis());
        User existing = knownUsers.get(ip);
        if (existing == null) {
            User user = new User(name == null ? ip : name, ip);
            user.setStatus(status);
            knownUsers.put(ip, user);
            listeners.forEach(l -> l.onUserJoined(user));
        } else {
            User.Status prev = existing.getStatus();
            existing.setStatus(status);
            if (prev != status) listeners.forEach(l -> l.onUserStatusChanged(existing));
        }
    }

    private void handleLeave(String ip) {
        User user = knownUsers.remove(ip);
        lastSeenTimes.remove(ip);
        if (user != null) {
            user.setStatus(User.Status.OFFLINE);
            listeners.forEach(l -> l.onUserLeft(user));
        }
    }

    private void reapTimedOut() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : new ArrayList<>(lastSeenTimes.entrySet())) {
            if (now - entry.getValue() > NetworkUtil.USER_TIMEOUT_MS) handleLeave(entry.getKey());
        }
    }

    private void fireError(String message) {
        listeners.forEach(l -> l.onError(message));
    }
}
