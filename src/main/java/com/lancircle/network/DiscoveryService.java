package com.lancircle.network;

import com.lancircle.core.MessageListener;
import com.lancircle.core.User;
import com.lancircle.util.JsonUtil;
import com.lancircle.util.NetworkUtil;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * UDP broadcast-based peer discovery.
 *
 * <p>Every {@code DISCOVERY_INTERVAL_MS} this service sends a JSON
 * {@code PRESENCE} datagram to the subnet broadcast address.  Any running
 * LAN Circle instance that hears it will register (or refresh) the sender
 * as an active peer.  When the app shuts down it sends a {@code LEAVE}
 * packet so peers update their lists immediately rather than waiting for
 * the timeout.</p>
 */
public class DiscoveryService {
    private static final Logger LOG = Logger.getLogger(DiscoveryService.class.getName());
    private static final int BUF = 1_024;

    private final String username;
    private final String localIp;

    private final Map<String, User> knownUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenTimes = new ConcurrentHashMap<>();
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private DatagramSocket txSocket;   // for sending broadcasts
    private DatagramSocket rxSocket;   // for receiving broadcasts
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile User.Status myStatus = User.Status.ONLINE;

    public DiscoveryService(String username, String localIp) {
        this.username = username;
        this.localIp = localIp;
    }

    private static void close(DatagramSocket s) {
        try {
            if (s != null && !s.isClosed()) s.close();
        } catch (Exception ignored) {
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void addListener(MessageListener l) {
        listeners.add(l);
    }

    public void start() throws SocketException {
        running = true;
        txSocket = new DatagramSocket();
        txSocket.setBroadcast(true);
        txSocket.setSoTimeout(500);

        rxSocket = new DatagramSocket(NetworkUtil.DISCOVERY_PORT);
        rxSocket.setBroadcast(true);
        rxSocket.setSoTimeout(500);

        scheduler = Executors.newScheduledThreadPool(3,
                r -> {
                    Thread t = new Thread(r, "discovery");
                    t.setDaemon(true);
                    return t;
                });

        // 1. Heartbeat broadcaster
        scheduler.scheduleAtFixedRate(this::broadcast,
                0, NetworkUtil.DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 2. Packet receiver (polls every 300 ms)
        scheduler.scheduleAtFixedRate(this::receive,
                150, 300, TimeUnit.MILLISECONDS);

        // 3. Timeout reaper (every 5 s)
        scheduler.scheduleAtFixedRate(this::reapTimedOut,
                5_000, 5_000, TimeUnit.MILLISECONDS);

        LOG.info("DiscoveryService started – " + localIp);
    }

    public void stop() {
        running = false;
        sendLeave();
        if (scheduler != null) scheduler.shutdownNow();
        close(txSocket);
        close(rxSocket);
    }

    public void setStatus(User.Status s) {
        myStatus = s;
        broadcast();       // push the change immediately
    }

    // ── Sending ───────────────────────────────────────────────────────────────

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

    // ── Receiving ────────────────────────────────────────────────────────────

    private void send(String payload) {
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            String addr = NetworkUtil.getBroadcastAddress();
            DatagramPacket pkt = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(addr), NetworkUtil.DISCOVERY_PORT);
            txSocket.send(pkt);
        } catch (Exception e) {
            if (running) LOG.warning("Broadcast error: " + e.getMessage());
        }
    }

    private void receive() {
        if (!running || rxSocket == null || rxSocket.isClosed()) return;
        try {
            byte[] buf = new byte[BUF];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            rxSocket.receive(pkt);

            String json = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
            Map<String, String> obj = JsonUtil.parseObject(json);
            String type = obj.get("type");
            String peerIp = obj.get("ip");
            String peerName = obj.get("username");

            if (peerIp.equals(localIp)) return;   // ignore self

            switch (type) {
                case "PRESENCE" -> handlePresence(peerIp, peerName,
                        User.Status.valueOf(obj.getOrDefault("status", "ONLINE")));
                case "LEAVE" -> handleLeave(peerIp, peerName);
            }
        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
            if (running) LOG.warning("Receive error: " + e.getMessage());
        }
    }

    private void handlePresence(String ip, String name, User.Status status) {
        lastSeenTimes.put(ip, System.currentTimeMillis());
        if (!knownUsers.containsKey(ip)) {
            User u = new User(name, ip);
            u.setStatus(status);
            knownUsers.put(ip, u);
            fire(l -> l.onUserJoined(u));
        } else {
            User u = knownUsers.get(ip);
            User.Status prev = u.getStatus();
            u.setStatus(status);
            if (prev != status) fire(l -> l.onUserStatusChanged(u));
        }
    }

    // ── Timeout reaping ───────────────────────────────────────────────────────

    private void handleLeave(String ip, String name) {
        User u = knownUsers.remove(ip);
        lastSeenTimes.remove(ip);
        if (u != null) {
            u.setStatus(User.Status.OFFLINE);
            fire(l -> l.onUserLeft(u));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void reapTimedOut() {
        long now = System.currentTimeMillis();
        long timeout = NetworkUtil.USER_TIMEOUT_MS;
        new ArrayList<>(lastSeenTimes.entrySet()).forEach(e -> {
            if (now - e.getValue() > timeout) {
                handleLeave(e.getKey(), "?");
            }
        });
    }

    private void fire(ListenerAction a) {
        listeners.forEach(a::run);
    }

    @FunctionalInterface
    interface ListenerAction {
        void run(MessageListener l);
    }
}
