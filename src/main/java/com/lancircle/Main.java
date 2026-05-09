package com.lancircle;

import com.lancircle.ui.LANCircleApp;

import javax.swing.*;

/**
 * LAN Circle — Lightweight Serverless LAN Messenger
 * Desktop entry point (Windows / macOS / Debian Linux).
 */
public class Main {
    public static void main(String[] args) {
        // macOS system-menu integration
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "LAN Circle");

        // Launch on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> new LANCircleApp().start());
    }
}