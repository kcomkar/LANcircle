package com.lancircle.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.lancircle.android.core.Message;
import com.lancircle.android.core.MessageListener;
import com.lancircle.android.core.User;
import com.lancircle.android.network.DiscoveryService;
import com.lancircle.android.network.MessageService;
import com.lancircle.android.transfer.FileTransferService;
import com.lancircle.android.util.NetworkUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements MessageListener {
    private static final int REQ_FILES = 101;
    private static final int REQ_FOLDER = 102;
    private static final int REQ_CAMERA = 103;
    private static final int REQ_PERMS = 104;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final List<User> users = new ArrayList<>();
    private final Map<String, User> usersByIp = new LinkedHashMap<>();
    private final Set<String> selectedIps = new LinkedHashSet<>();
    private ArrayAdapter<User> userAdapter;

    private TextView identityView;
    private TextView transcriptView;
    private TextView statusLine;
    private EditText composer;
    private EditText searchField;
    private Spinner statusSpinner;
    private ScrollView transcriptScroll;

    private String username;
    private String localIp;
    private Uri pendingCameraUri;
    private File logFile;

    private DiscoveryService discoveryService;
    private MessageService messageService;
    private FileTransferService fileTransferService;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestLanPermissions();
        localIp = NetworkUtil.getLocalIpAddress(this);
        buildUi();
        askUsername();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        shutdownServices();
        sendExecutor.shutdownNow();
    }

    @Override public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Leave LAN Circle?")
                .setPositiveButton("Leave", (d, w) -> finish())
                .setNegativeButton("Stay", null)
                .show();
    }

    private void requestLanPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_PERMS);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        identityView = label("LAN Circle - " + localIp, 18, true);
        top.addView(identityView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        statusSpinner = new Spinner(this);
        ArrayAdapter<User.Status> statusAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new User.Status[]{User.Status.ONLINE, User.Status.AWAY, User.Status.BUSY});
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusSpinner.setAdapter(statusAdapter);
        statusSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (discoveryService != null) discoveryService.setStatus((User.Status) parent.getItemAtPosition(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setHint("Search");
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) { highlightSearch(); }
        });
        controls.addView(statusSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.45f));
        controls.addView(searchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));
        top.addView(controls);
        root.addView(top);

        TextView userTitle = label("Users on this LAN", 14, true);
        root.addView(userTitle);
        ListView userList = new ListView(this);
        userList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        userAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, users);
        userList.setAdapter(userAdapter);
        userList.setOnItemClickListener((parent, view, position, id) -> {
            User user = users.get(position);
            if (selectedIps.contains(user.getIpAddress())) selectedIps.remove(user.getIpAddress());
            else selectedIps.add(user.getIpAddress());
        });
        root.addView(userList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        transcriptView = label("", 14, false);
        transcriptView.setTextColor(Color.rgb(24, 24, 27));
        transcriptView.setTextIsSelectable(true);
        transcriptScroll = new ScrollView(this);
        transcriptScroll.addView(transcriptView);
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        composer = new EditText(this);
        composer.setMinLines(2);
        composer.setMaxLines(4);
        composer.setHint("Type a message");
        root.addView(composer);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("B", v -> wrapSelection("**")), weight());
        buttons.addView(button("I", v -> wrapSelection("_")), weight());
        buttons.addView(button("Files", v -> pickFiles()), weight());
        buttons.addView(button("Folder", v -> pickFolder()), weight());
        buttons.addView(button("Camera", v -> captureImage()), weight());
        buttons.addView(button("Send", v -> sendText()), weight());
        root.addView(buttons);

        statusLine = label("Ready", 12, false);
        root.addView(statusLine);
        setContentView(root);
    }

    private void askUsername() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(Build.MODEL == null ? "" : Build.MODEL.replaceAll("\\s+", "-"));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("LAN Circle")
                .setMessage("LAN IP: " + localIp + "\nEnter a unique user name.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Next", null)
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                input.setError("Required");
                return;
            }
            username = value;
            dialog.dismiss();
            prepareLogFile();
            startServices();
        });
    }

    private void startServices() {
        identityView.setText("LAN Circle - " + username + " @ " + localIp);
        discoveryService = new DiscoveryService(this, username, localIp);
        messageService = new MessageService(username, localIp);
        fileTransferService = new FileTransferService(this);
        discoveryService.addListener(this);
        messageService.addListener(this);
        fileTransferService.addListener(this);
        try {
            messageService.start();
            fileTransferService.start();
            discoveryService.start();
            appendSystem("Joined LAN Circle at " + localIp);
            statusLine.setText("Downloads: " + fileTransferService.getDownloadDir().getAbsolutePath());
        } catch (Exception e) {
            appendSystem("Startup failed: " + e.getMessage());
        }
    }

    private void shutdownServices() {
        if (discoveryService != null) discoveryService.stop();
        if (messageService != null) messageService.stop();
        if (fileTransferService != null) fileTransferService.stop();
    }

    private void sendText() {
        List<String> ips = selectedIpList();
        String text = composer.getText().toString().trim();
        if (ips.isEmpty()) {
            toast("Select one or more users.");
            return;
        }
        if (text.isEmpty()) return;
        Message message = new Message(NetworkUtil.generateId(), username, localIp, ips, text,
                ips.size() > 1 ? Message.Type.GROUP : Message.Type.TEXT);
        sendExecutor.submit(() -> messageService.sendMessage(message, ips));
        appendMessage("Me", text, ips.size() > 1);
        composer.setText("");
    }

    private void pickFiles() {
        if (selectedIps.isEmpty()) {
            toast("Select one or more users.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_FILES);
    }

    private void pickFolder() {
        if (selectedIps.isEmpty()) {
            toast("Select one or more users.");
            return;
        }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
    }

    private void captureImage() {
        if (selectedIps.isEmpty()) {
            toast("Select one or more users.");
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "lan-circle-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        pendingCameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        startActivityForResult(intent, REQ_CAMERA);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_FILES && data != null) {
            handlePickedFiles(data);
        } else if (requestCode == REQ_FOLDER && data != null && data.getData() != null) {
            persistRead(data.getData());
            sendFolderTree(data.getData());
        } else if (requestCode == REQ_CAMERA && pendingCameraUri != null) {
            sendOneUri(pendingCameraUri, fileTransferService.displayName(pendingCameraUri),
                    Message.Type.IMAGE, fileTransferService.size(pendingCameraUri));
        }
    }

    private void handlePickedFiles(Intent data) {
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                persistRead(uri);
                sendOneUri(uri, fileTransferService.displayName(uri), guessType(uri), fileTransferService.size(uri));
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            persistRead(uri);
            sendOneUri(uri, fileTransferService.displayName(uri), guessType(uri), fileTransferService.size(uri));
        }
    }

    private void sendFolderTree(Uri treeUri) {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        String rootName = documentName(docUri);
        sendDocumentChildren(treeUri, docUri, rootName == null ? "folder" : rootName);
    }

    private void sendDocumentChildren(Uri treeUri, Uri parentDocUri, String pathPrefix) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                DocumentsContract.getDocumentId(parentDocUri));
        try (Cursor c = getContentResolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0L : c.getLong(3);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    sendDocumentChildren(treeUri, child, pathPrefix + "/" + name);
                } else {
                    sendOneUri(child, pathPrefix + "/" + name, Message.Type.FILE, size);
                }
            }
        }
    }

    private void sendOneUri(Uri uri, String remoteName, Message.Type type, long size) {
        List<String> ips = selectedIpList();
        fileTransferService.sendUri(uri, remoteName, fileNameOnly(remoteName), size, ips);
        Message notice = new Message(NetworkUtil.generateId(), username, localIp, ips,
                "Sending " + remoteName, type);
        notice.setFileName(fileNameOnly(remoteName));
        notice.setFileSize(size);
        sendExecutor.submit(() -> messageService.sendMessage(notice, ips));
        appendMessage("Me", "Sent " + remoteName + " (" + NetworkUtil.formatSize(size) + ")", ips.size() > 1);
    }

    private Message.Type guessType(Uri uri) {
        String type = getContentResolver().getType(uri);
        return type != null && type.startsWith("image/") ? Message.Type.IMAGE : Message.Type.FILE;
    }

    private void persistRead(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void wrapSelection(String marker) {
        int start = composer.getSelectionStart();
        int end = composer.getSelectionEnd();
        if (start == end) {
            composer.getText().insert(start, marker + marker);
            composer.setSelection(start + marker.length());
            return;
        }
        String selected = composer.getText().subSequence(start, end).toString();
        composer.getText().replace(start, end, marker + selected + marker);
    }

    private List<String> selectedIpList() {
        return new ArrayList<>(selectedIps);
    }

    private void refreshUsers() {
        users.clear();
        users.addAll(usersByIp.values());
        users.sort((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()));
        userAdapter.notifyDataSetChanged();
    }

    private void appendMessage(String sender, String text, boolean group) {
        appendLine("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] "
                + sender + (group ? " to group" : "") + ": " + text);
    }

    private void appendSystem(String text) {
        appendLine("* " + text);
    }

    private void appendLine(String line) {
        String current = transcriptView.getText().toString();
        transcriptView.setText(current + line + "\n");
        highlightSearch();
        log(line);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void highlightSearch() {
        String text = transcriptView.getText().toString();
        String term = searchField.getText().toString().trim();
        if (term.isEmpty()) {
            transcriptView.setText(text);
            return;
        }
        SpannableString span = new SpannableString(text);
        String lower = text.toLowerCase();
        String needle = term.toLowerCase();
        int index = lower.indexOf(needle);
        while (index >= 0) {
            span.setSpan(new BackgroundColorSpan(Color.rgb(255, 233, 142)), index, index + needle.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = lower.indexOf(needle, index + needle.length());
        }
        transcriptView.setText(span);
    }

    private void prepareLogFile() {
        File dir = new File(getExternalFilesDir(null), "LANCircle Logs");
        dir.mkdirs();
        logFile = new File(dir, "chat-" + LocalDate.now() + ".log");
    }

    private void log(String line) {
        if (logFile == null) return;
        try (FileOutputStream out = new FileOutputStream(logFile, true)) {
            out.write((LocalDateTime.now().format(LOG_TIME) + " " + line + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    @Override public void onMessageReceived(Message message) {
        runOnUiThread(() -> {
            String content = switch (message.getType()) {
                case FILE, IMAGE -> "Incoming " + message.getFileName() + " (" + message.getFormattedSize() + ")";
                default -> message.getContent();
            };
            appendMessage(message.getSenderUsername(), content, message.isGroupMessage());
        });
    }

    @Override public void onUserJoined(User user) {
        runOnUiThread(() -> {
            usersByIp.put(user.getIpAddress(), user);
            refreshUsers();
            appendSystem(user.getUsername() + " joined at " + user.getIpAddress());
        });
    }

    @Override public void onUserLeft(User user) {
        runOnUiThread(() -> {
            usersByIp.remove(user.getIpAddress());
            selectedIps.remove(user.getIpAddress());
            refreshUsers();
            appendSystem(user.getUsername() + " left the LAN");
        });
    }

    @Override public void onUserStatusChanged(User user) {
        runOnUiThread(() -> {
            usersByIp.put(user.getIpAddress(), user);
            refreshUsers();
            appendSystem(user.getUsername() + " is now " + user.getStatus());
        });
    }

    @Override public void onFileTransferProgress(String transferId, String fileName, long transferred, long total) {
        runOnUiThread(() -> {
            if (total > 0) statusLine.setText(fileName + ": " + (transferred * 100 / total) + "%");
        });
    }

    @Override public void onFileTransferComplete(String transferId, String fileName, String savePath) {
        runOnUiThread(() -> appendSystem("Transfer complete: " + fileName + " -> " + savePath));
    }

    @Override public void onFileTransferFailed(String transferId, String fileName, String reason) {
        runOnUiThread(() -> appendSystem("Transfer failed: " + fileName + " - " + reason));
    }

    @Override public void onError(String error) {
        runOnUiThread(() -> statusLine.setText(error));
    }

    private String documentName(Uri docUri) {
        try (Cursor c = getContentResolver().query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private static String fileNameOnly(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(0, dp(4), 0, dp(4));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
