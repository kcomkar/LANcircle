package com.lancircle.util;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Network utility helpers shared across all LAN Circle services.
 */
public final class NetworkUtil {

    // ── Well-known ports ──────────────────────────────────────────────────────
    /**
     * UDP broadcast port for peer discovery heartbeats.
     */
    public static final int DISCOVERY_PORT = 45_678;
    /**
     * TCP port for text / control messages.
     */
    public static final int MESSAGE_PORT = 45_679;
    /**
     * TCP port for binary file transfers.
     */
    public static final int FILE_PORT = 45_680;

    // ── Timing constants ──────────────────────────────────────────────────────
    /**
     * How often we broadcast our own presence (ms).
     */
    public static final int DISCOVERY_INTERVAL_MS = 5_000;
    /**
     * A peer is considered offline after this many ms of silence.
     */
    public static final int USER_TIMEOUT_MS = 15_000;

    private NetworkUtil() {
    }

    // ── IP helpers ────────────────────────────────────────────────────────────

    /**
     * Returns the best local LAN IPv4 address.
     * Prefers 192.168.x.x / 10.x.x.x over other private ranges.
     */
    public static String getLocalIpAddress() {
        try {
            List<String> candidates = new ArrayList<>();
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress())
                        candidates.add(a.getHostAddress());
                }
            }
            // Prefer typical home / office subnets
            for (String c : candidates)
                if (c.startsWith("192.168.") || c.startsWith("10.")) return c;
            return candidates.isEmpty() ? "127.0.0.1" : candidates.get(0);
        } catch (SocketException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Returns the subnet broadcast address that corresponds to our LAN IP,
     * falling back to the limited broadcast 255.255.255.255.
     */
    public static String getBroadcastAddress() {
        try {
            String local = getLocalIpAddress();
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                for (InterfaceAddress ia : ifaces.nextElement().getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address
                            && ia.getAddress().getHostAddress().equals(local)
                            && ia.getBroadcast() != null)
                        return ia.getBroadcast().getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return "255.255.255.255";
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    /**
     * Generates a compact 12-character hex unique identifier.
     */
    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ── Size formatting ───────────────────────────────────────────────────────

    public static String formatSize(long bytes) {
        if (bytes < 1_024) return bytes + " B";
        if (bytes < 1_048_576) return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}