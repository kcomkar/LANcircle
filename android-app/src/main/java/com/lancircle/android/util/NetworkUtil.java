package com.lancircle.android.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.UUID;

public final class NetworkUtil {
    public static final int DISCOVERY_PORT = 45_678;
    public static final int MESSAGE_PORT = 45_679;
    public static final int FILE_PORT = 45_680;
    public static final int DISCOVERY_INTERVAL_MS = 5_000;
    public static final int USER_TIMEOUT_MS = 15_000;

    private NetworkUtil() {}

    public static String getLocalIpAddress(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            LinkProperties props = network == null ? null : cm.getLinkProperties(network);
            if (props != null) {
                for (LinkAddress address : props.getLinkAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (inet instanceof Inet4Address && !inet.isLoopbackAddress() && !inet.isLinkLocalAddress()) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public static String getBroadcastAddress(Context context) {
        String ip = getLocalIpAddress(context);
        if ("127.0.0.1".equals(ip)) return "255.255.255.255";
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return "255.255.255.255";
        return parts[0] + "." + parts[1] + "." + parts[2] + ".255";
    }

    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1_024) return bytes + " B";
        if (bytes < 1_048_576) return String.format("%.1f KB", bytes / 1_024.0);
        if (bytes < 1_073_741_824L) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}
