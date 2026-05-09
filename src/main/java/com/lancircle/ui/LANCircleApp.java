package com.lancircle.ui;

import com.lancircle.core.Message;
import com.lancircle.core.MessageListener;
import com.lancircle.core.User;
import com.lancircle.network.DiscoveryService;
import com.lancircle.network.MessageService;
import com.lancircle.transfer.FileTransferService;
import com.lancircle.util.NetworkUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Swing desktop shell for LAN Circle.
 */
public class LANCircleApp implements MessageListener {
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JFrame frame;
    private JTable userTable;
    private UserTableModel userModel;
    private JTextPane transcript;
    private JTextArea composer;
    private JTextField searchField;
    private JLabel statusLine;
    private JComboBox<User.Status> statusCombo;

    private String username;
    private String localIp;
    private DiscoveryService discoveryService;
    private MessageService messageService;
    private FileTransferService fileTransferService;
    private File logFile;

    private static boolean isImage(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp");
    }

    private static long folderSize(File folder) {
        try {
            return Files.walk(folder.toPath()).filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public void start() {
        localIp = NetworkUtil.getLocalIpAddress();
        username = askUsername();
        if (username == null) return;

        prepareLogFile();
        buildWindow();
        startServices();
        frame.setVisible(true);
    }

    private String askUsername() {
        JTextField nameField = new JTextField(System.getProperty("user.name", ""), 22);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("LAN IP: " + localIp), BorderLayout.NORTH);
        panel.add(nameField, BorderLayout.CENTER);

        while (true) {
            int result = JOptionPane.showConfirmDialog(null, panel, "LAN Circle - choose a unique user name",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return null;
            String value = nameField.getText().trim();
            if (!value.isBlank()) return value;
            JOptionPane.showMessageDialog(null, "Enter a user name before joining the LAN.",
                    "LAN Circle", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void buildWindow() {
        frame = new JFrame("LAN Circle");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(940, 620));
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        userModel = new UserTableModel();
        userTable = new JTable(userModel);
        userTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        userTable.setRowHeight(28);
        userTable.setAutoCreateRowSorter(true);

        transcript = new JTextPane();
        transcript.setEditable(false);
        transcript.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));

        composer = new JTextArea(4, 40);
        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setTransferHandler(new FileDropHandler());
        composer.setBorder(new EmptyBorder(8, 8, 8, 8));

        searchField = new JTextField(18);
        searchField.getDocument().addDocumentListener((SimpleDocumentListener) e -> highlightSearch());

        statusLine = new JLabel("Ready. Downloads: " + new File(System.getProperty("user.home"), "LANCircle Downloads"));
        statusCombo = new JComboBox<>(new User.Status[]{User.Status.ONLINE, User.Status.AWAY, User.Status.BUSY});
        statusCombo.addActionListener(e -> {
            if (discoveryService != null) discoveryService.setStatus((User.Status) statusCombo.getSelectedItem());
        });

        frame.setJMenuBar(menuBar());
        frame.setContentPane(content());
        frame.pack();
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem attach = new JMenuItem("Attach files or folders...");
        attach.addActionListener(e -> chooseAndSendFiles());
        JMenuItem screenshot = new JMenuItem("Share screenshot");
        screenshot.addActionListener(e -> captureAndSendScreenshot());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> shutdown());
        file.add(attach);
        file.add(screenshot);
        file.addSeparator();
        file.add(exit);
        bar.add(file);
        return bar;
    }

    private JComponent content() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new BorderLayout(8, 8));
        JLabel identity = new JLabel("LAN Circle - " + username + " @ " + localIp);
        identity.setFont(identity.getFont().deriveFont(Font.BOLD, 15f));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(new JLabel("Status"));
        right.add(statusCombo);
        right.add(new JLabel("Search"));
        right.add(searchField);
        top.add(identity, BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, usersPanel(), chatPanel());
        split.setResizeWeight(0.30);
        split.setDividerLocation(300);

        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(statusLine, BorderLayout.SOUTH);
        return root;
    }

    private JComponent usersPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("Discovered users and LAN IP addresses"), BorderLayout.NORTH);
        panel.add(new JScrollPane(userTable), BorderLayout.CENTER);
        JButton selectAll = new JButton("Select all");
        selectAll.addActionListener(e -> {
            if (userTable.getRowCount() > 0) userTable.setRowSelectionInterval(0, userTable.getRowCount() - 1);
        });
        panel.add(selectAll, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent chatPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JScrollPane(transcript), BorderLayout.CENTER);

        JPanel composerPanel = new JPanel(new BorderLayout(6, 6));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton bold = new JButton("B");
        bold.setFont(bold.getFont().deriveFont(Font.BOLD));
        bold.addActionListener(e -> wrapSelection("**"));
        JButton italic = new JButton("I");
        italic.setFont(italic.getFont().deriveFont(Font.ITALIC));
        italic.addActionListener(e -> wrapSelection("_"));
        JButton attach = new JButton("Attach");
        attach.addActionListener(e -> chooseAndSendFiles());
        JButton screenshot = new JButton("Screenshot");
        screenshot.addActionListener(e -> captureAndSendScreenshot());
        JButton send = new JButton("Send");
        send.addActionListener(e -> sendText());
        tools.add(bold);
        tools.add(italic);
        tools.add(attach);
        tools.add(screenshot);
        tools.add(send);

        composerPanel.add(new JScrollPane(composer), BorderLayout.CENTER);
        composerPanel.add(tools, BorderLayout.SOUTH);
        panel.add(composerPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void startServices() {
        discoveryService = new DiscoveryService(username, localIp);
        messageService = new MessageService(username, localIp);
        fileTransferService = new FileTransferService();
        discoveryService.addListener(this);
        messageService.addListener(this);
        fileTransferService.addListener(this);

        try {
            messageService.start();
            fileTransferService.start();
            discoveryService.start();
            appendSystem("Joined the LAN as " + username + " (" + localIp + ")");
        } catch (Exception e) {
            appendSystem("Startup failed: " + e.getMessage());
            JOptionPane.showMessageDialog(frame, "Could not start LAN services: " + e.getMessage(),
                    "LAN Circle", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void sendText() {
        List<String> ips = selectedIps();
        String text = composer.getText().trim();
        if (ips.isEmpty()) {
            showStatus("Select one or more users first.");
            return;
        }
        if (text.isBlank()) return;

        Message message = new Message(NetworkUtil.generateId(), username, localIp, ips, text,
                ips.size() > 1 ? Message.Type.GROUP : Message.Type.TEXT);
        messageService.sendMessage(message, ips);
        appendMessage("Me", text, ips.size() > 1);
        composer.setText("");
    }

    private void chooseAndSendFiles() {
        List<String> ips = selectedIps();
        if (ips.isEmpty()) {
            showStatus("Select one or more users first.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            sendFiles(List.of(chooser.getSelectedFiles()));
        }
    }

    private void sendFiles(List<File> files) {
        List<String> ips = selectedIps();
        if (ips.isEmpty()) {
            showStatus("Select one or more users first.");
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                fileTransferService.sendFolder(file, ips);
                sendFileNotice(file, ips, Message.Type.FILE);
            } else {
                fileTransferService.sendFile(file, ips);
                sendFileNotice(file, ips, isImage(file) ? Message.Type.IMAGE : Message.Type.FILE);
            }
        }
    }

    private void sendFileNotice(File file, List<String> ips, Message.Type type) {
        Message notice = new Message(NetworkUtil.generateId(), username, localIp, ips,
                "Sending " + file.getName(), type);
        notice.setFileName(file.getName());
        notice.setFileSize(file.isFile() ? file.length() : folderSize(file));
        messageService.sendMessage(notice, ips);
        appendMessage("Me", "Sent " + file.getName() + " (" + NetworkUtil.formatSize(notice.getFileSize()) + ")",
                ips.size() > 1);
    }

    private void captureAndSendScreenshot() {
        List<String> ips = selectedIps();
        if (ips.isEmpty()) {
            showStatus("Select one or more users first.");
            return;
        }
        try {
            Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = new Robot().createScreenCapture(screen);
            File out = File.createTempFile("lan-circle-screenshot-", ".png");
            ImageIO.write(image, "png", out);
            out.deleteOnExit();
            fileTransferService.sendFile(out, ips);
            sendFileNotice(out, ips, Message.Type.IMAGE);
        } catch (Exception e) {
            showStatus("Screenshot failed: " + e.getMessage());
        }
    }

    private List<String> selectedIps() {
        int[] rows = userTable.getSelectedRows();
        Set<String> ips = new LinkedHashSet<>();
        for (int row : rows) {
            int modelRow = userTable.convertRowIndexToModel(row);
            ips.add(userModel.getUser(modelRow).getIpAddress());
        }
        return new ArrayList<>(ips);
    }

    private void wrapSelection(String marker) {
        int start = composer.getSelectionStart();
        int end = composer.getSelectionEnd();
        if (start == end) {
            composer.insert(marker + marker, start);
            composer.setCaretPosition(start + marker.length());
            return;
        }
        String selected = composer.getSelectedText();
        composer.replaceRange(marker + selected + marker, start, end);
    }

    private void highlightSearch() {
        Highlighter highlighter = transcript.getHighlighter();
        highlighter.removeAllHighlights();
        String term = searchField.getText().trim().toLowerCase();
        if (term.isBlank()) return;
        String all = transcript.getText().toLowerCase();
        int index = all.indexOf(term);
        while (index >= 0) {
            try {
                highlighter.addHighlight(index, index + term.length(),
                        new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 233, 142)));
            } catch (BadLocationException ignored) {
            }
            index = all.indexOf(term, index + term.length());
        }
    }

    private void appendMessage(String sender, String text, boolean group) {
        appendStyled("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] "
                + sender + (group ? " to group" : "") + ": ", true);
        appendStyled(text + "\n", false);
        log(sender + ": " + text);
    }

    private void appendSystem(String text) {
        appendStyled("* " + text + "\n", false);
        log("SYSTEM: " + text);
    }

    private void appendStyled(String text, boolean bold) {
        StyledDocument doc = transcript.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setBold(attrs, bold);
        try {
            doc.insertString(doc.getLength(), text, attrs);
            transcript.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    private void prepareLogFile() {
        File dir = new File(System.getProperty("user.home"), "LANCircle Logs");
        dir.mkdirs();
        logFile = new File(dir, "chat-" + LocalDate.now() + ".log");
    }

    private void log(String line) {
        if (logFile == null) return;
        try {
            Files.writeString(logFile.toPath(), LocalDateTime.now().format(LOG_TIME) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private void showStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLine.setText(text));
    }

    private void shutdown() {
        if (discoveryService != null) discoveryService.stop();
        if (messageService != null) messageService.stop();
        if (fileTransferService != null) fileTransferService.stop();
        if (frame != null) frame.dispose();
        System.exit(0);
    }

    @Override
    public void onMessageReceived(Message message) {
        SwingUtilities.invokeLater(() -> {
            String content = switch (message.getType()) {
                case FILE, IMAGE -> "Incoming " + message.getFileName() + " (" + message.getFormattedSize() + ")";
                default -> message.getContent();
            };
            appendMessage(message.getSenderUsername(), content, message.isGroupMessage());
        });
    }

    @Override
    public void onUserJoined(User user) {
        SwingUtilities.invokeLater(() -> {
            userModel.upsert(user);
            appendSystem(user.getUsername() + " joined at " + user.getIpAddress());
        });
    }

    @Override
    public void onUserLeft(User user) {
        SwingUtilities.invokeLater(() -> {
            userModel.remove(user);
            appendSystem(user.getUsername() + " left the LAN");
        });
    }

    @Override
    public void onUserStatusChanged(User user) {
        SwingUtilities.invokeLater(() -> {
            userModel.upsert(user);
            appendSystem(user.getUsername() + " is now " + user.getStatus());
        });
    }

    @Override
    public void onFileTransferProgress(String transferId, String fileName, long transferred, long total) {
        if (total > 0) showStatus(fileName + ": " + (transferred * 100 / total) + "%");
    }

    @Override
    public void onFileTransferComplete(String transferId, String fileName, String savePath) {
        SwingUtilities.invokeLater(() -> appendSystem("Transfer complete: " + fileName + " -> " + savePath));
    }

    @Override
    public void onFileTransferFailed(String transferId, String fileName, String reason) {
        SwingUtilities.invokeLater(() -> appendSystem("Transfer failed: " + fileName + " - " + reason));
    }

    @Override
    public void onError(String error) {
        showStatus(error);
    }

    @FunctionalInterface
    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void update(javax.swing.event.DocumentEvent e);

        @Override
        default void insertUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(javax.swing.event.DocumentEvent e) {
            update(e);
        }
    }

    private static final class UserTableModel extends AbstractTableModel {
        private final String[] columns = {"User", "LAN IP", "Status", "Last seen"};
        private final List<User> users = new ArrayList<>();

        User getUser(int row) {
            return users.get(row);
        }

        void upsert(User user) {
            int existing = users.indexOf(user);
            if (existing >= 0) {
                users.set(existing, user);
                fireTableRowsUpdated(existing, existing);
            } else {
                users.add(user);
                users.sort(Comparator.comparing(User::getUsername, String.CASE_INSENSITIVE_ORDER));
                fireTableDataChanged();
            }
        }

        void remove(User user) {
            int existing = users.indexOf(user);
            if (existing >= 0) {
                users.remove(existing);
                fireTableRowsDeleted(existing, existing);
            }
        }

        @Override
        public int getRowCount() {
            return users.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            User user = users.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> user.getUsername();
                case 1 -> user.getIpAddress();
                case 2 -> user.getStatus();
                case 3 -> user.getFormattedLastSeen();
                default -> "";
            };
        }
    }

    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                sendFiles(files);
                return true;
            } catch (Exception e) {
                showStatus("Drop failed: " + e.getMessage());
                return false;
            }
        }
    }
}
