package rs.local.fileshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import androidx.core.content.FileProvider;

import java.net.URLEncoder;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "LanSmbCopy";
    private static final int PICK_UPLOAD_FILE = 1001;
    private static final int TAKE_PHOTO = 1002;
    private static final int PICK_PHONE_FILE = 1003;
    private static final int PHONE_PORT = 8989;
    private static final int HOTSPOT_START_TIMEOUT_MS = 7000;
    private static final String DEFAULT_HOTSPOT_SSID = "fileshare";
    private static final String DEFAULT_HOTSPOT_PASSWORD = "12345678";
    private static final String PHONE_ROOT_MARKER = "__PHONE_ROOT__";
    private static final String PREFERRED_HOST = "192.168.0.32";
    private static final String[] COMMON_SHARES = {"Documents", "Downloads", "Users", "Public", "Share", "Shared", "share", "Download", "Files", "PhoneShare"};

    private final ExecutorService io = Executors.newFixedThreadPool(32);
    private final List<Device> devices = new ArrayList<>();
    private final List<RemoteItem> items = new ArrayList<>();
    private final LinkedHashSet<String> cachedPcShares = new LinkedHashSet<>();
    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> fileAdapter;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout transferOverlay;
    private TransferProgressView transferCircle;
    private TextView transferText;
    private TextView transferLabel;
    private EditText ipInput;
    private EditText shareInput;
    private LinearLayout pcControls;
    private LinearLayout phoneControls;
    private LinearLayout sortBar;
    private TextView fileLabel;
    private TextView storageLabel;
    private ListView fileList;
    private LinearLayout bottomBar;
    private Button bottomPaste;
    private Button bottomBack;
    private CIFSContext cifsContext;
    private String currentHost = "";
    private String currentShare = "";
    private String currentPath = "";
    private String phoneTargetHost = "";
    private java.io.File pendingPhotoFile;
    private ServerSocket phoneServerSocket;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ConnectivityManager.NetworkCallback phoneNetworkCallback;
    private volatile boolean phoneServerRunning;
    private String hotspotSsid = "";
    private String hotspotPassword = "";
    private final AtomicBoolean autoConnectRunning = new AtomicBoolean(false);
    private SortMode sortMode = SortMode.NAME;
    private boolean sortAscending = true;
    private boolean localMode = false;
    private boolean pendingMyPhoneAfterSettings = false;
    private java.io.File currentLocalDir;
    private CopySelection copySelection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onDestroy() {
        stopPhoneReceiver();
        stopHotspot();
        releasePhoneNetwork();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingMyPhoneAfterSettings && hasPhoneStorageAccess()) {
            pendingMyPhoneAfterSettings = false;
            openMyPhone();
        }
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(242, 242, 247));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(12), 0);
        header.setBackgroundColor(Color.rgb(248, 248, 248));
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("ic_launcher", "drawable", getPackageName()));
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        header.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("  DaneR file share");
        title.setTextColor(Color.rgb(28, 28, 30));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setBackground(makeRounded(Color.rgb(229, 229, 234), 16));
        modeBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button phoneMode = new Button(this);
        phoneMode.setText("Phone");
        Button pcMode = new Button(this);
        pcMode.setText("PC");
        styleButton(phoneMode);
        styleButton(pcMode);
        modeBar.addView(phoneMode, new LinearLayout.LayoutParams(0, dp(54), 1));
        modeBar.addView(pcMode, new LinearLayout.LayoutParams(0, dp(54), 1));
        panel.addView(modeBar);

        pcControls = new LinearLayout(this);
        pcControls.setOrientation(LinearLayout.VERTICAL);
        pcControls.setVisibility(View.GONE);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, -2);
        controlsParams.setMargins(0, dp(10), 0, 0);
        panel.addView(pcControls, controlsParams);

        ipInput = new EditText(this);
        ipInput.setHint("Address: 192.168.0.32");
        ipInput.setText(PREFERRED_HOST);
        ipInput.setSingleLine(true);
        styleInput(ipInput);
        pcControls.addView(ipInput, new LinearLayout.LayoutParams(-1, dp(46)));

        shareInput = new EditText(this);
        shareInput.setSingleLine(true);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setBackground(makeRounded(Color.rgb(229, 229, 234), 16));
        actions.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button scan = new Button(this);
        scan.setText("Scan");
        Button connect = new Button(this);
        connect.setText("Open");
        Button camera = new Button(this);
        camera.setText("Camera");
        styleButton(scan);
        styleButton(connect);
        styleButton(camera);
        actions.addView(scan, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(connect, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(camera, new LinearLayout.LayoutParams(0, -2, 1));
        pcControls.addView(actions);

        phoneControls = new LinearLayout(this);
        phoneControls.setOrientation(LinearLayout.VERTICAL);
        phoneControls.setVisibility(View.GONE);
        LinearLayout.LayoutParams phoneParams = new LinearLayout.LayoutParams(-1, -2);
        phoneParams.setMargins(0, dp(10), 0, 0);
        panel.addView(phoneControls, phoneParams);

        LinearLayout phoneActions = new LinearLayout(this);
        phoneActions.setOrientation(LinearLayout.HORIZONTAL);
        phoneActions.setBackground(makeRounded(Color.rgb(229, 229, 234), 16));
        phoneActions.setPadding(dp(3), dp(3), dp(3), dp(3));
        Button startPhone = new Button(this);
        startPhone.setText("Start");
        Button connectPhone = new Button(this);
        connectPhone.setText("Connect");
        Button sendPhone = new Button(this);
        sendPhone.setText("Send");
        styleButton(startPhone);
        styleButton(connectPhone);
        styleButton(sendPhone);
        phoneActions.addView(startPhone, new LinearLayout.LayoutParams(0, -2, 1));
        phoneActions.addView(connectPhone, new LinearLayout.LayoutParams(0, -2, 1));
        phoneActions.addView(sendPhone, new LinearLayout.LayoutParams(0, -2, 1));
        phoneControls.addView(phoneActions);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        panel.addView(progress);

        status = new TextView(this);
        status.setText("Izaberi Phone ili PC.");
        status.setTextColor(Color.rgb(60, 60, 67));
        status.setTextSize(14);
        status.setBackground(makeRounded(Color.WHITE, 14));
        status.setPadding(dp(12), 0, dp(12), 0);
        status.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, dp(42));
        statusParams.setMargins(0, dp(10), 0, dp(10));
        panel.addView(status, statusParams);

        sortBar = new LinearLayout(this);
        sortBar.setOrientation(LinearLayout.HORIZONTAL);
        sortBar.setBackground(makeRounded(Color.rgb(229, 229, 234), 16));
        sortBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        sortBar.setVisibility(View.GONE);
        Button sortName = new Button(this);
        sortName.setText("Name");
        Button sortDate = new Button(this);
        sortDate.setText("Date");
        Button sortSize = new Button(this);
        sortSize.setText("Size");
        styleThinButton(sortName);
        styleThinButton(sortDate);
        styleThinButton(sortSize);
        sortBar.addView(sortName, new LinearLayout.LayoutParams(0, dp(36), 1));
        sortBar.addView(sortDate, new LinearLayout.LayoutParams(0, dp(36), 1));
        sortBar.addView(sortSize, new LinearLayout.LayoutParams(0, dp(36), 1));
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(-1, dp(42));
        sortParams.setMargins(0, 0, 0, dp(8));
        panel.addView(sortBar, sortParams);

        ListView devList = new ListView(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        devList.setAdapter(deviceAdapter);
        devList.setBackgroundColor(Color.WHITE);
        devList.setDividerHeight(0);

        fileLabel = new TextView(this);
        fileLabel.setText("Files and folders");
        styleSection(fileLabel);
        fileLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams fileLabelParams = new LinearLayout.LayoutParams(-1, dp(32));
        fileLabelParams.setMargins(0, dp(8), 0, 0);
        panel.addView(fileLabel, fileLabelParams);

        storageLabel = new TextView(this);
        storageLabel.setText("");
        storageLabel.setTextColor(Color.rgb(99, 99, 102));
        storageLabel.setTextSize(13);
        storageLabel.setGravity(Gravity.CENTER_VERTICAL);
        storageLabel.setPadding(dp(8), 0, dp(8), 0);
        storageLabel.setVisibility(View.GONE);
        panel.addView(storageLabel, new LinearLayout.LayoutParams(-1, dp(24)));

        fileList = new ListView(this);
        fileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        fileList.setAdapter(fileAdapter);
        fileList.setBackgroundColor(Color.WHITE);
        fileList.setDividerHeight(0);
        fileList.setVisibility(View.GONE);
        panel.addView(fileList, new LinearLayout.LayoutParams(-1, 0, 2));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setVisibility(View.GONE);
        bottomPaste = new Button(this);
        bottomPaste.setText("Paste");
        styleButton(bottomPaste);
        bottomPaste.setVisibility(View.GONE);
        bottomBack = new Button(this);
        bottomBack.setText("Back");
        styleButton(bottomBack);
        bottomBar.addView(bottomPaste, new LinearLayout.LayoutParams(dp(150), dp(52)));
        Space bottomSpace = new Space(this);
        bottomBar.addView(bottomSpace, new LinearLayout.LayoutParams(0, dp(52), 1));
        bottomBar.addView(bottomBack, new LinearLayout.LayoutParams(dp(150), dp(52)));
        panel.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(58)));

        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        buildTransferOverlay(frame);
        setContentView(frame);

        phoneMode.setOnClickListener(v -> showPhoneMode());
        pcMode.setOnClickListener(v -> showPcMode());
        scan.setOnClickListener(v -> scanNetwork(true));
        connect.setOnClickListener(v -> connectToHost(ipInput.getText().toString().trim()));
        bottomPaste.setOnClickListener(v -> pasteSelection());
        bottomBack.setOnClickListener(v -> goUp());
        camera.setOnClickListener(v -> takePhoto());
        startPhone.setOnClickListener(v -> {
            if (hotspotReservation == null) startPhoneServer();
            else stopPhoneServer();
        });
        connectPhone.setOnClickListener(v -> showConnectHotspotDialog());
        sendPhone.setOnClickListener(v -> pickPhoneFile());
        sortName.setOnClickListener(v -> changeSort(SortMode.NAME));
        sortDate.setOnClickListener(v -> changeSort(SortMode.DATE));
        sortSize.setOnClickListener(v -> changeSort(SortMode.SIZE));
        devList.setOnItemClickListener((p, v, pos, id) -> connectToHost(devices.get(pos).host));
        fileList.setOnItemClickListener((p, v, pos, id) -> openRemoteItem(pos));
        fileList.setOnItemLongClickListener((p, v, pos, id) -> {
            showItemOptions(pos);
            return true;
        });
        fileList.setOnLongClickListener(v -> {
            showPasteOptions();
            return true;
        });
        fileLabel.setOnLongClickListener(v -> {
            showPasteOptions();
            return true;
        });
    }

    private void buildTransferOverlay(FrameLayout frame) {
        transferOverlay = new LinearLayout(this);
        transferOverlay.setOrientation(LinearLayout.VERTICAL);
        transferOverlay.setGravity(Gravity.CENTER);
        transferOverlay.setPadding(dp(22), dp(18), dp(22), dp(18));
        transferOverlay.setBackground(makeRounded(Color.WHITE, 20));
        transferOverlay.setVisibility(View.GONE);

        transferCircle = new TransferProgressView(this);
        transferText = new TextView(this);
        transferText.setText("0%");
        transferText.setTextColor(Color.rgb(28, 28, 30));
        transferText.setTypeface(Typeface.DEFAULT_BOLD);
        transferText.setTextSize(22);
        transferText.setGravity(Gravity.CENTER);

        FrameLayout circleBox = new FrameLayout(this);
        circleBox.addView(transferCircle, new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER));
        circleBox.addView(transferText, new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER));
        transferOverlay.addView(circleBox, new LinearLayout.LayoutParams(dp(112), dp(112)));

        transferLabel = new TextView(this);
        transferLabel.setTextColor(Color.rgb(60, 60, 67));
        transferLabel.setTextSize(14);
        transferLabel.setGravity(Gravity.CENTER);
        transferLabel.setSingleLine(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(dp(230), -2);
        labelParams.setMargins(0, dp(10), 0, 0);
        transferOverlay.addView(transferLabel, labelParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(280), -2, Gravity.CENTER);
        frame.addView(transferOverlay, params);
    }

    private void showPhoneMode() {
        phoneControls.setVisibility(View.VISIBLE);
        pcControls.setVisibility(View.GONE);
        showWorkArea();
        items.clear();
        fileAdapter.clear();
        showPhoneRoot();
    }

    private void showPcMode() {
        pcControls.setVisibility(View.VISIBLE);
        phoneControls.setVisibility(View.GONE);
        showWorkArea();
        localMode = false;
        items.clear();
        fileAdapter.clear();
        if (cifsContext != null && currentHost != null && currentHost.length() > 0) {
            if (currentShare != null && currentShare.length() > 0) listFiles();
            else if (!cachedPcShares.isEmpty()) showCachedShares();
            else listShares();
        } else {
            String host = ipInput.getText().toString().trim();
            if (host.length() == 0) host = PREFERRED_HOST;
            connectToHost(host);
        }
    }

    private void showWorkArea() {
        sortBar.setVisibility(View.VISIBLE);
        fileLabel.setVisibility(View.VISIBLE);
        storageLabel.setVisibility(View.VISIBLE);
        fileList.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        bottomBack.setVisibility(View.VISIBLE);
        updatePasteButton();
    }

    private void updatePasteButton() {
        if (bottomPaste != null) bottomPaste.setVisibility(copySelection == null ? View.GONE : View.VISIBLE);
    }

    private void styleInput(EditText input) {
        input.setTextSize(14);
        input.setTextColor(Color.rgb(28, 28, 30));
        input.setHintTextColor(Color.rgb(142, 142, 147));
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(makeRounded(Color.WHITE, 14));
        input.setSingleLine(true);
    }

    private void styleButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.rgb(0, 122, 255));
        button.setBackground(makeRounded(Color.WHITE, 14));
    }

    private void styleThinButton(Button button) {
        styleButton(button);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
    }

    private void styleSection(TextView view) {
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(99, 99, 102));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setBackgroundColor(Color.TRANSPARENT);
    }

    private GradientDrawable makeBorder(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(1, stroke);
        drawable.setCornerRadius(0);
        return drawable;
    }

    private GradientDrawable makeRounded(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void scanNetwork(boolean autoOpen) {
        devices.clear();
        closeSmb();
        autoConnectRunning.set(false);
        currentPath = "";
        runOnUiThread(() -> {
            deviceAdapter.clear();
            fileAdapter.clear();
            setBusy(true, autoOpen ? "Trazim PC i pokusavam automatsko povezivanje..." : "Skeniram mrezu za SMB...");
        });

        String prefix = getWifiPrefix();
        if (prefix == null) {
            setBusy(false, "Nisam nasao Wi-Fi IP adresu.");
            return;
        }

        final int[] left = {254};
        for (int i = 1; i <= 254; i++) {
            String host = prefix + i;
            io.execute(() -> {
                try {
                    if (isPortOpen(host, 445, 250)) addDevice(new Device(host));
                } finally {
                    synchronized (left) {
                        left[0]--;
                        if (left[0] == 0) {
                            if (autoOpen) autoConnectDiscovered();
                            else runOnUiThread(() -> setBusy(false, "Skeniranje gotovo. Nadjeno: " + devices.size()));
                        }
                    }
                }
            });
        }
    }

    private void connectPreferredThenScan() {
        setBusy(true, "Pokusavam ovaj PC //" + PREFERRED_HOST + "...");
        io.execute(() -> {
            closeSmb();
            if (!tryConnectToHost(PREFERRED_HOST, false)) {
                runOnUiThread(() -> scanNetwork(true));
            }
        });
    }

    private String getWifiPrefix() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null || wm.getConnectionInfo() == null) return null;
        int ip = wm.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return String.format(Locale.US, "%d.%d.%d.", ip & 255, (ip >> 8) & 255, (ip >> 16) & 255);
    }

    private String getWifiIpAddress() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null || wm.getConnectionInfo() == null) return "0.0.0.0";
        int ip = wm.getConnectionInfo().getIpAddress();
        if (ip == 0) return "0.0.0.0";
        return String.format(Locale.US, "%d.%d.%d.%d", ip & 255, (ip >> 8) & 255, (ip >> 16) & 255, (ip >> 24) & 255);
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void addDevice(Device d) {
        synchronized (devices) {
            for (Device existing : devices) if (existing.host.equals(d.host)) return;
            devices.add(d);
        }
        runOnUiThread(() -> deviceAdapter.add(d.host + "  SMB/Windows sharing"));
    }

    private void connectToHost(String host) {
        if (host.length() == 0) {
            toast("Unesi IP ili izaberi uredjaj.");
            return;
        }
        setBusy(true, "Povezujem se na " + host + " bez user/pass...");
        io.execute(() -> {
        closeSmb();
            tryConnectToHost(host, true);
        });
    }

    private String readHttpHeaders(InputStream in) throws Exception {
        byte[] data = new byte[8192];
        int count = 0;
        int state = 0;
        while (count < data.length) {
            int b = in.read();
            if (b < 0) break;
            data[count++] = (byte) b;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
        return new String(data, 0, count, "UTF-8");
    }

    private String readAllText(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        return sb.toString();
    }

    private String headerValue(String headers, String name) {
        String prefix = name.toLowerCase(Locale.US) + ":";
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase(Locale.US).startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private int headerInt(String headers, String name) {
        try {
            return Integer.parseInt(headerValue(headers, name));
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLongValue(String text, String key) {
        int start = text.indexOf(key);
        if (start < 0) return -1;
        start += key.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        try {
            return Long.parseLong(text.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private void writeHttp(OutputStream out, String statusLine, String body) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        String headers = "HTTP/1.1 " + statusLine + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private void copyFixed(InputStream in, OutputStream out, int length) throws Exception {
        byte[] buf = new byte[65536];
        int left = length;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) throw new RuntimeException("Prekinut transfer.");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private String safeFileName(String name) {
        String cleaned = name.replace('\\', '_').replace('/', '_').trim();
        if (cleaned.length() == 0 || ".".equals(cleaned) || "..".equals(cleaned)) return "phone-file.bin";
        return cleaned;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.SIZE);
                if (ix >= 0 && !c.isNull(ix)) return c.getLong(ix);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void autoConnectDiscovered() {
        if (!autoConnectRunning.compareAndSet(false, true)) return;
        List<Device> snapshot;
        synchronized (devices) {
            snapshot = new ArrayList<>(devices);
        }
        Collections.sort(snapshot, (a, b) -> {
            if (PREFERRED_HOST.equals(a.host)) return -1;
            if (PREFERRED_HOST.equals(b.host)) return 1;
            return a.host.compareTo(b.host);
        });
        if (snapshot.isEmpty()) {
            runOnUiThread(() -> setBusy(false, "Nisam nasao PC sa otvorenim SMB portom 445."));
            autoConnectRunning.set(false);
            return;
        }
        io.execute(() -> {
            runOnUiThread(() -> setBusy(true, "Nadjen SMB. Proveravam guest share..."));
            for (Device d : snapshot) {
                closeSmb();
                runOnUiThread(() -> setBusy(true, "Pokusavam //" + d.host + " bez user/pass..."));
                if (tryConnectToHost(d.host, false)) {
                    autoConnectRunning.set(false);
                    return;
                }
            }
            autoConnectRunning.set(false);
            setBusy(false, "Nadjen je SMB, ali nijedan guest share nije otvoren. Upisi IP/share rucno ili dozvoli Everyone/Guest na Windows-u.");
        });
    }

    private boolean tryConnectToHost(String host, boolean showError) {
        try {
            String manualShare = shareInput.getText().toString().trim();
            String[] shares = manualShare.length() > 0 ? new String[]{manualShare} : COMMON_SHARES;
            Exception last = null;
            CIFSContext base = SingletonContext.getInstance();
            CIFSContext[] authModes = new CIFSContext[]{
                    base.withGuestCrendentials(),
                    base.withAnonymousCredentials(),
                    base.withCredentials(new NtlmPasswordAuthenticator("", "Guest", "")),
                    base.withCredentials(new NtlmPasswordAuthenticator(host, "Guest", "")),
                    base.withCredentials(new NtlmPasswordAuthenticator("DANER", "Guest", ""))
            };
            for (CIFSContext auth : authModes) {
                cifsContext = auth;
                try {
                    for (String shareName : shares) {
                        try {
                            SmbFile root = new SmbFile(smbUrl(host, shareName, ""), cifsContext);
                            root.listFiles();
                            currentHost = host;
                            currentShare = "";
                            currentPath = "";
                            cachedPcShares.add(shareName);
                            runOnUiThread(() -> {
                                ipInput.setText(host);
                                if (manualShare.length() > 0) shareInput.setText(shareName);
                                showCachedShares();
                            });
                            updatePcStorageAsync(host, shareName, "");
                            return true;
                        } catch (Exception e) {
                            last = e;
                            Log.w(TAG, "Share failed host=" + host + " share=" + shareName, e);
                        }
                    }
                } catch (Exception e) {
                    last = e;
                    Log.w(TAG, "Auth failed host=" + host, e);
                } finally {
                    if (currentHost.length() == 0) closeSmb();
                }
            }
            throw last == null ? new RuntimeException("Nije nadjen guest share") : last;
        } catch (Exception e) {
            closeSmb();
            if (showError) setBusy(false, "SMB ne moze bez lozinke ili share nije nadjen: " + cleanError(e));
            return false;
        }
    }

    private void listFiles() {
        localMode = false;
        if (cifsContext == null) {
            toast("Prvo otvori IP.");
            return;
        }
        if (currentShare.length() == 0) {
            listShares();
            return;
        }
        setBusy(true, "Citam //" + currentHost + "/" + currentShare + "/" + currentPath);
        io.execute(() -> {
            try {
                List<RemoteItem> parsed = new ArrayList<>();
                SmbFile folder = new SmbFile(smbUrl(currentHost, currentShare, currentPath), cifsContext);
                long freeBytes = folder.getDiskFreeSpace();
                for (SmbFile f : folder.listFiles()) {
                    String name = f.getName();
                    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    if (".".equals(name) || "..".equals(name)) continue;
                    boolean dir = f.isDirectory();
                    String path = currentPath.length() == 0 ? name : currentPath + "/" + name;
                    parsed.add(new RemoteItem(dir, name, path, f.length(), f.lastModified(), false));
                }
                sortRemoteItems(parsed);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(parsed);
                    fileAdapter.clear();
                    for (RemoteItem item : items) fileAdapter.add(item.label());
                    showStorage("PC //" + currentHost + "/" + currentShare, freeBytes, -1);
                    setBusy(false, "Otvoreno: //" + currentHost + "/" + currentShare + "/" + currentPath);
                });
            } catch (Exception e) {
                setBusy(false, "Greska citanja SMB foldera: " + cleanError(e));
            }
        });
    }

    private void listShares() {
        localMode = false;
        if (cifsContext == null || currentHost.length() == 0) {
            toast("Prvo otvori IP.");
            return;
        }
        if (!cachedPcShares.isEmpty()) {
            showCachedShares();
            return;
        }
        setBusy(true, "Citam deljene foldere na //" + currentHost);
        io.execute(() -> {
            try {
                List<RemoteItem> parsed = new ArrayList<>();
                HashSet<String> seen = new HashSet<>();
                try {
                    SmbFile server = new SmbFile("smb://" + currentHost + "/", cifsContext);
                    for (SmbFile share : server.listFiles()) {
                        String name = share.getName();
                        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                        if (name.length() == 0 || name.endsWith("$")) continue;
                        parsed.add(new RemoteItem(true, name, "", 0, share.lastModified(), true));
                        seen.add(name.toLowerCase(Locale.US));
                        cachedPcShares.add(name);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Share enumeration failed, probing common shares", e);
                }
                for (String shareName : COMMON_SHARES) {
                    if (seen.contains(shareName.toLowerCase(Locale.US))) continue;
                    try {
                        SmbFile root = new SmbFile(smbUrl(currentHost, shareName, ""), cifsContext);
                        root.listFiles();
                        parsed.add(new RemoteItem(true, shareName, "", 0, 0, true));
                        seen.add(shareName.toLowerCase(Locale.US));
                        cachedPcShares.add(shareName);
                    } catch (Exception ignored) {
                    }
                }
                sortRemoteItems(parsed);
                runOnUiThread(() -> {
                    currentShare = "";
                    currentPath = "";
                    items.clear();
                    items.addAll(parsed);
                    fileAdapter.clear();
                    for (RemoteItem item : items) fileAdapter.add(item.label());
                    setBusy(false, "Deljeni folderi na //" + currentHost);
                });
            } catch (Exception e) {
                setBusy(false, "Greska citanja share-ova: " + cleanError(e));
            }
        });
    }

    private void showCachedShares() {
        List<RemoteItem> parsed = new ArrayList<>();
        for (String shareName : cachedPcShares) parsed.add(new RemoteItem(true, shareName, "", 0, 0, true));
        sortRemoteItems(parsed);
        items.clear();
        items.addAll(parsed);
        fileAdapter.clear();
        for (RemoteItem item : items) fileAdapter.add(item.label());
        progress.setVisibility(View.GONE);
        if (!cachedPcShares.isEmpty()) updatePcStorageAsync(currentHost, cachedPcShares.iterator().next(), "");
    }

    private void openRemoteItem(int pos) {
        if (localMode) {
            openLocalItem(pos);
            return;
        }
        RemoteItem item = items.get(pos);
        if (item.shareRoot) {
            currentShare = item.name;
            currentPath = "";
            listFiles();
        } else if (item.dir) {
            currentPath = item.path;
            listFiles();
        } else {
            toast("Drzi fajl 2 sekunde za Open/Copy/Delete.");
        }
    }

    private void goUp() {
        if (localMode) {
            if (currentLocalDir == null || currentLocalDir.getParentFile() == null) {
                showPhoneRoot();
                return;
            }
            listLocalFiles(currentLocalDir.getParentFile());
            return;
        }
        if (currentShare == null || currentShare.isEmpty()) {
            listShares();
            return;
        }
        if (currentPath == null || currentPath.isEmpty()) {
            currentShare = "";
            listShares();
            return;
        }
        int ix = currentPath.lastIndexOf('/');
        currentPath = ix <= 0 ? "" : currentPath.substring(0, ix);
        listFiles();
    }

    private void showItemOptions(int pos) {
        if (localMode) {
            showLocalItemOptions(pos);
            return;
        }
        RemoteItem item = items.get(pos);
        String[] options = item.dir ? new String[]{"Copy", "Delete"} : new String[]{"Open", "Copy", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(options, (dialog, which) -> {
                    if (!item.dir && which == 0) {
                        openRemoteFile(item);
                        return;
                    }
                    int action = item.dir ? which : which - 1;
                    if (action == 0) copyRemote(item);
                    if (action == 1) confirmDelete(item);
                })
                .show();
    }

    private void openMyPhone() {
        localMode = true;
        if (!hasPhoneStorageAccess()) {
            showStorageAccessDialog();
            return;
        }
        java.io.File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = getFilesDir();
        listLocalFiles(root);
    }

    private void showPhoneRoot() {
        localMode = true;
        currentLocalDir = null;
        items.clear();
        items.add(new RemoteItem(true, "root", PHONE_ROOT_MARKER, 0, 0, false));
        fileAdapter.clear();
        fileAdapter.add(items.get(0).label());
        java.io.File root = Environment.getExternalStorageDirectory();
        showStorage("Phone", root == null ? -1 : root.getFreeSpace(), root == null ? -1 : root.getTotalSpace());
    }

    private boolean hasPhoneStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showStorageAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("My Phone")
                .setMessage("Za otvaranje foldera na telefonu ukljuci All files access za LAN File Share.")
                .setNegativeButton("App folder", (dialog, which) -> {
                    java.io.File fallback = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (fallback == null) fallback = getFilesDir();
                    listLocalFiles(fallback);
                })
                .setPositiveButton("Open settings", (dialog, which) -> openStorageSettings())
                .show();
    }

    private void openStorageSettings() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 55);
                return;
            }
            startActivity(intent);
            pendingMyPhoneAfterSettings = true;
            setBusy(false, "Ukljuci All files access, pa ponovo klikni My Phone.");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                toast("Ne mogu da otvorim storage settings.");
            }
        }
    }

    private void listLocalFiles(java.io.File dir) {
        localMode = true;
        currentLocalDir = dir;
        setBusy(true, "Citam My Phone: " + dir.getAbsolutePath());
        io.execute(() -> {
            try {
                java.io.File[] children = dir.listFiles();
                if (children == null) throw new RuntimeException("Folder nije dostupan");
                long freeBytes = dir.getFreeSpace();
                long totalBytes = dir.getTotalSpace();
                List<RemoteItem> parsed = new ArrayList<>();
                for (java.io.File child : children) {
                    parsed.add(new RemoteItem(child.isDirectory(), child.getName(), child.getAbsolutePath(), child.length(), child.lastModified(), false));
                }
                sortRemoteItems(parsed);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(parsed);
                    fileAdapter.clear();
                    for (RemoteItem item : items) fileAdapter.add(item.label());
                    showStorage("Phone", freeBytes, totalBytes);
                    setBusy(false, "My Phone > " + dir.getAbsolutePath());
                });
            } catch (Exception e) {
                setBusy(false, "Ne mogu da otvorim folder: " + cleanError(e));
            }
        });
    }

    private void openLocalItem(int pos) {
        RemoteItem item = items.get(pos);
        if (PHONE_ROOT_MARKER.equals(item.path)) {
            openMyPhone();
            return;
        }
        if (item.dir) {
            listLocalFiles(new java.io.File(item.path));
        } else {
            toast("Drzi fajl 2 sekunde za Open/Copy/Delete.");
        }
    }

    private void openLocalFile(RemoteItem item) {
        io.execute(() -> {
            try {
                java.io.File source = new java.io.File(item.path);
                java.io.File out = prepareOpenFile(item.name);
                TransferProgress transfer = beginTransfer("Otvaram " + item.name, source.length());
                try (InputStream in = new FileInputStream(source); OutputStream os = new FileOutputStream(out)) {
                    copyStream(in, os, transfer);
                }
                hideTransferProgress();
                openPreparedFile(out);
                setBusy(false, "Otvoreno: " + item.name);
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Open greska: " + cleanError(e));
            }
        });
    }

    private void openRemoteFile(RemoteItem item) {
        io.execute(() -> {
            try {
                SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, item.path), cifsContext);
                java.io.File out = prepareOpenFile(item.name);
                TransferProgress transfer = beginTransfer("Skidam za otvaranje " + item.name, remote.length());
                try (InputStream in = remote.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    copyStream(in, os, transfer);
                }
                hideTransferProgress();
                openPreparedFile(out);
                setBusy(false, "Otvoreno: " + item.name);
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Open greska: " + cleanError(e));
            }
        });
    }

    private java.io.File prepareOpenFile(String name) {
        java.io.File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getFilesDir();
        java.io.File dir = new java.io.File(base, "open-cache");
        if (!dir.exists()) dir.mkdirs();
        return uniqueFile(dir, name);
    }

    private void openPreparedFile(java.io.File file) {
        runOnUiThread(() -> {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mimeForName(file.getName()));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Open " + file.getName()));
            } catch (Exception e) {
                toast("Nema aplikacije za ovaj fajl: " + cleanError(e));
            }
        });
    }

    private String mimeForName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "*/*";
    }

    private void showLocalItemOptions(int pos) {
        RemoteItem item = items.get(pos);
        if (PHONE_ROOT_MARKER.equals(item.path)) {
            toast("Otvori root, pa izaberi fajl.");
            return;
        }
        String[] options = item.dir ? new String[]{"Copy", "Delete"} : new String[]{"Open", "Copy", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(options, (dialog, which) -> {
                    if (!item.dir && which == 0) {
                        openLocalFile(item);
                        return;
                    }
                    int action = item.dir ? which : which - 1;
                    if (action == 0) copyLocalToPhone(item);
                    if (action == 1) confirmDeleteLocal(item);
                })
                .show();
    }

    private void copyLocalToPhone(RemoteItem item) {
        java.io.File file = new java.io.File(item.path);
        copySelection = CopySelection.local(file);
        updatePasteButton();
        toast("Kopirano: " + item.name + ". Klikni Paste levo dole.");
    }

    private void showPasteOptions() {
        if (copySelection == null) {
            toast("Prvo izaberi Copy na fajlu ili folderu.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Paste")
                .setItems(new String[]{"Paste"}, (dialog, which) -> pasteSelection())
                .show();
    }

    private void pasteSelection() {
        if (copySelection == null) {
            toast("Nema nista za Paste.");
            return;
        }
        if (localMode) {
            pasteToLocal();
        } else {
            pasteToRemote();
        }
    }

    private void pasteToLocal() {
        if (currentLocalDir == null) {
            toast("Otvori root folder pa uradi Paste.");
            return;
        }
        CopySelection source = copySelection;
        io.execute(() -> {
            try {
                java.io.File target = uniqueFile(currentLocalDir, source.name);
                TransferProgress transfer = beginTransfer("Paste: " + source.name, copyTotalBytes(source));
                if (source.local) {
                    copyLocalRecursive(new java.io.File(source.localPath), target, transfer);
                } else if (source.dir) {
                    copyRemoteFolderToLocal(source.remoteHost, source.remoteShare, source.remotePath, target, transfer);
                } else {
                    SmbFile remote = new SmbFile(smbUrl(source.remoteHost, source.remoteShare, source.remotePath), cifsContext);
                    try (InputStream in = remote.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                        copyStream(in, out, transfer);
                    }
                }
                hideTransferProgress();
                runOnUiThread(() -> listLocalFiles(currentLocalDir));
                setBusy(false, "Paste gotovo: " + target.getName());
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Paste greska: " + cleanError(e));
            }
        });
    }

    private void pasteToRemote() {
        if (cifsContext == null || currentShare == null || currentShare.length() == 0) {
            toast("Otvori PC share/folder pa uradi Paste.");
            return;
        }
        CopySelection source = copySelection;
        io.execute(() -> {
            try {
                String targetPath = uniqueRemotePath(currentShare, currentPath, source.name);
                TransferProgress transfer = beginTransfer("Paste: " + source.name, copyTotalBytes(source));
                if (source.local) {
                    java.io.File local = new java.io.File(source.localPath);
                    if (source.dir) copyLocalFolderToRemote(local, targetPath, transfer);
                    else copyLocalFileToRemote(local, targetPath, transfer);
                } else if (source.dir) {
                    copyRemoteFolderToRemote(source.remoteHost, source.remoteShare, source.remotePath, currentHost, currentShare, targetPath, transfer);
                } else {
                    copyRemoteFileToRemote(source.remoteHost, source.remoteShare, source.remotePath, currentHost, currentShare, targetPath, transfer);
                }
                hideTransferProgress();
                runOnUiThread(this::listFiles);
                setBusy(false, "Paste gotovo: " + source.name);
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Paste greska: " + cleanError(e));
            }
        });
    }

    private void confirmDeleteLocal(RemoteItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Obrisati \"" + item.name + "\" sa telefona?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteLocal(item))
                .show();
    }

    private void deleteLocal(RemoteItem item) {
        setBusy(true, "Brisem " + item.name);
        io.execute(() -> {
            try {
                deleteLocalRecursive(new java.io.File(item.path));
                runOnUiThread(() -> listLocalFiles(currentLocalDir));
                setBusy(false, "Obrisano: " + item.name);
            } catch (Exception e) {
                setBusy(false, "Delete greska: " + cleanError(e));
            }
        });
    }

    private void deleteLocalRecursive(java.io.File target) {
        if (target.isDirectory()) {
            java.io.File[] children = target.listFiles();
            if (children != null) for (java.io.File child : children) deleteLocalRecursive(child);
        }
        if (!target.delete()) throw new RuntimeException("Ne mogu da obrisem " + target.getName());
    }

    private void copyRemote(RemoteItem item) {
        String shareName = item.shareRoot ? item.name : currentShare;
        String remotePath = item.shareRoot ? "" : item.path;
        copySelection = CopySelection.remote(currentHost, shareName, remotePath, item.name, item.dir || item.shareRoot);
        updatePasteButton();
        toast("Kopirano: " + item.name + ". Klikni Paste levo dole.");
    }

    private void copyFolder(String shareName, String remotePath, String folderName) {
        io.execute(() -> {
            try {
                java.io.File baseDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (baseDir == null) baseDir = getFilesDir();
                java.io.File target = uniqueFile(baseDir, folderName);
                TransferProgress transfer = beginTransfer("Kopiram folder " + folderName, remoteSizeRecursive(currentHost, shareName, remotePath));
                copyFolderRecursive(shareName, remotePath, target, transfer);
                hideTransferProgress();
                setBusy(false, "Folder kopiran: " + target.getAbsolutePath());
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Copy greska: " + cleanError(e));
            }
        });
    }

    private void copyFolderRecursive(String shareName, String remotePath, java.io.File targetDir, TransferProgress transfer) throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs()) throw new RuntimeException("Ne mogu da napravim folder " + targetDir);
        SmbFile folder = new SmbFile(smbUrl(currentHost, shareName, remotePath), cifsContext);
        for (SmbFile child : folder.listFiles()) {
            String name = child.getName();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.length() == 0 || ".".equals(name) || "..".equals(name)) continue;
            String childPath = remotePath.length() == 0 ? name : remotePath + "/" + name;
            java.io.File local = new java.io.File(targetDir, name);
            if (child.isDirectory()) {
                copyFolderRecursive(shareName, childPath, local, transfer);
            } else {
                try (InputStream in = child.getInputStream(); OutputStream os = new FileOutputStream(local)) {
                    copyStream(in, os, transfer);
                }
            }
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws Exception {
        copyStream(in, out, null);
    }

    private void copyStream(InputStream in, OutputStream out, TransferProgress transfer) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (transfer != null) transfer.add(n);
        }
    }

    private void copyLocalRecursive(java.io.File source, java.io.File target) throws Exception {
        copyLocalRecursive(source, target, null);
    }

    private void copyLocalRecursive(java.io.File source, java.io.File target, TransferProgress transfer) throws Exception {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new RuntimeException("Ne mogu da napravim folder " + target.getName());
            java.io.File[] children = source.listFiles();
            if (children != null) {
                for (java.io.File child : children) copyLocalRecursive(child, new java.io.File(target, child.getName()), transfer);
            }
        } else {
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
                copyStream(in, out, transfer);
            }
        }
    }

    private void copyRemoteFolderToLocal(String host, String shareName, String remotePath, java.io.File targetDir, TransferProgress transfer) throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs()) throw new RuntimeException("Ne mogu da napravim folder " + targetDir.getName());
        SmbFile folder = new SmbFile(smbUrl(host, shareName, remotePath), cifsContext);
        for (SmbFile child : folder.listFiles()) {
            String name = child.getName();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.length() == 0 || ".".equals(name) || "..".equals(name)) continue;
            String childPath = joinPath(remotePath, name);
            java.io.File local = new java.io.File(targetDir, name);
            if (child.isDirectory()) copyRemoteFolderToLocal(host, shareName, childPath, local, transfer);
            else {
                try (InputStream in = child.getInputStream(); OutputStream out = new FileOutputStream(local)) {
                    copyStream(in, out, transfer);
                }
            }
        }
    }

    private void copyLocalFileToRemote(java.io.File source, String targetPath, TransferProgress transfer) throws Exception {
        SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, targetPath), cifsContext);
        try (InputStream in = new FileInputStream(source); OutputStream out = remote.getOutputStream()) {
            copyStream(in, out, transfer);
        }
    }

    private void copyLocalFolderToRemote(java.io.File source, String targetPath, TransferProgress transfer) throws Exception {
        SmbFile remoteDir = new SmbFile(smbUrl(currentHost, currentShare, targetPath), cifsContext);
        if (!remoteDir.exists()) remoteDir.mkdirs();
        java.io.File[] children = source.listFiles();
        if (children == null) return;
        for (java.io.File child : children) {
            String childTarget = joinPath(targetPath, child.getName());
            if (child.isDirectory()) copyLocalFolderToRemote(child, childTarget, transfer);
            else copyLocalFileToRemote(child, childTarget, transfer);
        }
    }

    private void copyRemoteFileToRemote(String sourceHost, String sourceShare, String sourcePath, String targetHost, String targetShare, String targetPath, TransferProgress transfer) throws Exception {
        SmbFile source = new SmbFile(smbUrl(sourceHost, sourceShare, sourcePath), cifsContext);
        SmbFile target = new SmbFile(smbUrl(targetHost, targetShare, targetPath), cifsContext);
        try (InputStream in = source.getInputStream(); OutputStream out = target.getOutputStream()) {
            copyStream(in, out, transfer);
        }
    }

    private void copyRemoteFolderToRemote(String sourceHost, String sourceShare, String sourcePath, String targetHost, String targetShare, String targetPath, TransferProgress transfer) throws Exception {
        SmbFile targetDir = new SmbFile(smbUrl(targetHost, targetShare, targetPath), cifsContext);
        if (!targetDir.exists()) targetDir.mkdirs();
        SmbFile sourceDir = new SmbFile(smbUrl(sourceHost, sourceShare, sourcePath), cifsContext);
        for (SmbFile child : sourceDir.listFiles()) {
            String name = child.getName();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            if (name.length() == 0 || ".".equals(name) || "..".equals(name)) continue;
            String childSource = joinPath(sourcePath, name);
            String childTarget = joinPath(targetPath, name);
            if (child.isDirectory()) copyRemoteFolderToRemote(sourceHost, sourceShare, childSource, targetHost, targetShare, childTarget, transfer);
            else copyRemoteFileToRemote(sourceHost, sourceShare, childSource, targetHost, targetShare, childTarget, transfer);
        }
    }

    private long copyTotalBytes(CopySelection source) throws Exception {
        if (source.local) return localSizeRecursive(new java.io.File(source.localPath));
        return remoteSizeRecursive(source.remoteHost, source.remoteShare, source.remotePath);
    }

    private long localSizeRecursive(java.io.File source) {
        if (source == null || !source.exists()) return 0;
        if (source.isFile()) return Math.max(0, source.length());
        long total = 0;
        java.io.File[] children = source.listFiles();
        if (children != null) {
            for (java.io.File child : children) total += localSizeRecursive(child);
        }
        return total;
    }

    private long remoteSizeRecursive(String host, String shareName, String remotePath) throws Exception {
        return remoteSizeRecursive(new SmbFile(smbUrl(host, shareName, remotePath), cifsContext));
    }

    private long remoteSizeRecursive(SmbFile source) throws Exception {
        if (!source.isDirectory()) return Math.max(0, source.length());
        long total = 0;
        for (SmbFile child : source.listFiles()) total += remoteSizeRecursive(child);
        return total;
    }

    private TransferProgress beginTransfer(String label, long totalBytes) {
        TransferProgress transfer = new TransferProgress(label, Math.max(0, totalBytes));
        updateTransferProgress(transfer);
        return transfer;
    }

    private void updateTransferProgress(TransferProgress transfer) {
        long total = transfer.totalBytes;
        long copied = Math.min(transfer.copiedBytes, total);
        int percent = total <= 0 ? 0 : (int) Math.min(100, (copied * 100) / total);
        runOnUiThread(() -> {
            progress.setVisibility(View.GONE);
            transferOverlay.setVisibility(View.VISIBLE);
            transferCircle.setProgress(percent);
            transferText.setText(percent + "%");
            transferLabel.setText(transfer.label);
            status.setText(transfer.label + "  " + percent + "%");
        });
    }

    private void hideTransferProgress() {
        runOnUiThread(() -> transferOverlay.setVisibility(View.GONE));
    }

    private String uniqueRemotePath(String shareName, String parentPath, String name) throws Exception {
        String candidate = joinPath(parentPath, name);
        if (!new SmbFile(smbUrl(currentHost, shareName, candidate), cifsContext).exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            candidate = joinPath(parentPath, base + "-" + i + ext);
            if (!new SmbFile(smbUrl(currentHost, shareName, candidate), cifsContext).exists()) return candidate;
        }
    }

    private String joinPath(String parent, String name) {
        if (parent == null || parent.length() == 0) return name;
        return parent + "/" + name;
    }

    private void confirmDelete(RemoteItem item) {
        if (item.shareRoot) {
            toast("Ne brisem root share. Udji u folder pa izaberi fajl/folder.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Obrisati \"" + item.name + "\" sa PC-ja?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteRemote(item))
                .show();
    }

    private void deleteRemote(RemoteItem item) {
        setBusy(true, "Brisem " + item.name);
        io.execute(() -> {
            try {
                SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, item.path), cifsContext);
                deleteRecursive(remote);
                runOnUiThread(this::listFiles);
                setBusy(false, "Obrisano: " + item.name);
            } catch (Exception e) {
                setBusy(false, "Delete greska: " + cleanError(e));
            }
        });
    }

    private void deleteRecursive(SmbFile target) throws Exception {
        if (target.isDirectory()) {
            for (SmbFile child : target.listFiles()) deleteRecursive(child);
        }
        target.delete();
    }

    private void changeSort(SortMode mode) {
        if (sortMode == mode) {
            sortAscending = !sortAscending;
        } else {
            sortMode = mode;
            sortAscending = true;
        }
        if (localMode) {
            if (currentLocalDir != null) listLocalFiles(currentLocalDir);
            return;
        }
        if (currentShare == null || currentShare.length() == 0) listShares();
        else listFiles();
    }

    private void sortRemoteItems(List<RemoteItem> list) {
        Collections.sort(list, (a, b) -> {
            if (a.shareRoot != b.shareRoot) return a.shareRoot ? -1 : 1;
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int result;
            if (sortMode == SortMode.DATE) {
                result = Long.compare(a.modified, b.modified);
            } else if (sortMode == SortMode.SIZE) {
                result = Long.compare(a.sizeBytes, b.sizeBytes);
            } else {
                result = a.name.compareToIgnoreCase(b.name);
            }
            if (result == 0) result = a.name.compareToIgnoreCase(b.name);
            return sortAscending ? result : -result;
        });
    }

    private enum SortMode {
        NAME,
        DATE,
        SIZE
    }

    private void download(RemoteItem item) {
        io.execute(() -> {
            try {
                SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, item.path), cifsContext);
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                java.io.File out = uniqueFile(dir, item.name);
                TransferProgress transfer = beginTransfer("Skidam " + item.name, remote.length());
                try (InputStream in = remote.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    copyStream(in, os, transfer);
                }
                hideTransferProgress();
                setBusy(false, "Sacuvano: " + out.getAbsolutePath());
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Download greska: " + cleanError(e));
            }
        });
    }

    private java.io.File uniqueFile(java.io.File dir, String name) {
        java.io.File f = new java.io.File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new java.io.File(dir, base + "-" + i + ext);
            if (!f.exists()) return f;
        }
    }

    private void pickUploadFile() {
        if (cifsContext == null || currentShare.length() == 0) {
            toast("Prvo otvori IP.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_UPLOAD_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_UPLOAD_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) upload(data.getData());
        if (requestCode == TAKE_PHOTO && resultCode == RESULT_OK && pendingPhotoFile != null && pendingPhotoFile.exists()) uploadLocalFile(pendingPhotoFile);
        if (requestCode == PICK_PHONE_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) sendFileToPhone(data.getData());
    }

    private void showPhoneDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView info = new TextView(this);
        info.setText("Start server pali hotspot i prijem fajlova. Connect to server skenira hotspot mreze i pokusava default password " + DEFAULT_HOTSPOT_PASSWORD + ".");
        info.setTextSize(14);
        info.setTextColor(Color.rgb(99, 99, 102));
        info.setPadding(0, 0, 0, dp(12));
        box.addView(info, new LinearLayout.LayoutParams(-1, -2));

        Button startServer = dialogButton(hotspotReservation == null ? "Start server" : "Stop server", true);
        Button connectServer = dialogButton("Connect to server", false);
        Button sendFile = dialogButton("Send file", false);

        box.addView(startServer, dialogButtonParams());
        box.addView(connectServer, dialogButtonParams());
        box.addView(sendFile, dialogButtonParams());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Phone")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();

        startServer.setOnClickListener(v -> {
            dialog.dismiss();
            if (hotspotReservation == null) startPhoneServer();
            else stopPhoneServer();
        });
        connectServer.setOnClickListener(v -> {
            dialog.dismiss();
            showConnectHotspotDialog();
        });
        sendFile.setOnClickListener(v -> {
            dialog.dismiss();
            pickPhoneFile();
        });
        dialog.show();
    }

    private Button dialogButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(0, 122, 255));
        button.setBackground(makeRounded(primary ? Color.rgb(0, 122, 255) : Color.rgb(242, 242, 247), 16));
        return button;
    }

    private LinearLayout.LayoutParams dialogButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private void startPhoneServer() {
        startHotspot();
    }

    private void stopPhoneServer() {
        stopHotspot();
        stopPhoneReceiver();
    }

    private void showSystemHotspotDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Start server")
                .setMessage("Server za fajlove je ukljucen.\n\nOtvorice se Mobile network > Personal hotspot. Tamo postavi:\nName: " + DEFAULT_HOTSPOT_SSID + "\nPassword: " + DEFAULT_HOTSPOT_PASSWORD)
                .setNegativeButton("Stay here", null)
                .setPositiveButton("Open Settings", (dialog, which) -> openHotspotSettings())
                .show();
    }

    private boolean ensureWifiPermissions() {
        if (Build.VERSION.SDK_INT < 23) return true;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 2001);
            toast("Dozvoli WiFi/Location pa ponovi komandu.");
            return false;
        }
        if (!isLocationEnabled()) {
            showLocationRequiredDialog();
            return false;
        }
        return true;
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private void showLocationRequiredDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Location is off")
                .setMessage("Android ne dozvoljava hotspot/WiFi scan dok je Location ugasen. Ukljuci Location pa ponovi Start server ili Connect to server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open Settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .show();
    }

    private void startHotspot() {
        if (!ensureWifiPermissions()) return;
        if (Build.VERSION.SDK_INT < 26) {
            toast("Hotspot iz aplikacije trazi Android 8 ili noviji.");
            return;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) {
            toast("WiFi nije dostupan.");
            return;
        }
        setBusy(true, "Startujem lokalni hotspot...");
        if (!phoneServerRunning) startPhoneReceiver();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (phoneServerRunning && hotspotReservation == null) {
                setBusy(false, "Receiver radi, ali Android jos nije vratio hotspot SSID/password.");
            }
        }, HOTSPOT_START_TIMEOUT_MS);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    try {
                        hotspotReservation = reservation;
                        WifiConfiguration config = reservation.getWifiConfiguration();
                        hotspotSsid = config == null ? "" : cleanWifiValue(config.SSID);
                        hotspotPassword = config == null ? "" : cleanWifiValue(config.preSharedKey);
                        setBusy(false, "Server ON  WiFi: " + hotspotSsid + "  Pass: " + hotspotPassword);
                        showHotspotInfo();
                    } catch (Exception e) {
                        setBusy(false, "Server start greska: " + cleanError(e));
                    }
                }

                @Override
                public void onStopped() {
                    hotspotReservation = null;
                    hotspotSsid = "";
                    hotspotPassword = "";
                    setBusy(false, "Server zaustavljen.");
                }

                @Override
                public void onFailed(int reason) {
                    hotspotReservation = null;
                    stopPhoneReceiver();
                    setBusy(false, "Hotspot ne moze da se startuje. Ukljuci Location/WiFi i probaj opet. Kod: " + reason);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            stopPhoneReceiver();
            setBusy(false, "Huawei je odbio hotspot: " + cleanError(e));
        }
    }

    private void openHotspotSettings() {
        if (tryStartSettingsAction("android.settings.MOBILE_NETWORK_LIST")) return;
        if (tryStartSettingsAction("android.settings.NETWORK_PROVIDER_SETTINGS")) return;
        if (tryStartSettingsAction(Settings.ACTION_WIRELESS_SETTINGS)) return;
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception ignored) {
            toast("Otvori rucno Settings > Mobile network > Personal hotspot.");
        }
    }

    private boolean tryStartSettingsAction(String action) {
        try {
            Intent intent = new Intent(action);
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopHotspot() {
        try {
            if (hotspotReservation != null) hotspotReservation.close();
        } catch (Exception ignored) {
        }
        hotspotReservation = null;
        hotspotSsid = "";
        hotspotPassword = "";
    }

    private void showHotspotInfo() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("Hotspot started")
                .setMessage("Na drugom telefonu idi Phone > Connect hotspot.\n\nSSID: " + hotspotSsid + "\nPassword: " + hotspotPassword)
                .setPositiveButton("OK", null)
                .show());
    }

    private void showConnectHotspotDialog() {
        if (!ensureWifiPermissions()) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm == null) {
            toast("WiFi nije dostupan.");
            return;
        }
        setBusy(true, "Skeniram hotspot mreze...");
        try {
            wm.startScan();
        } catch (Exception ignored) {
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> showWifiScanResults(wm), 2200);
    }

    private void showWifiScanResults(WifiManager wm) {
        try {
            List<ScanResult> scans = wm.getScanResults();
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (ScanResult scan : scans) {
                if (scan.SSID != null && scan.SSID.trim().length() > 0) names.add(scan.SSID.trim());
            }
            List<String> list = new ArrayList<>(names);
            Collections.sort(list, String::compareToIgnoreCase);
            list.add(0, "Manual SSID");
            if (list.size() == 1) {
                setBusy(false, "Nema WiFi mreza u listi. Proveri da su WiFi i Location ukljuceni.");
                showNoWifiNetworksDialog();
                return;
            }
            setBusy(false, "Izaberi hotspot za povezivanje.");
            new AlertDialog.Builder(this)
                    .setTitle("Connect to server")
                    .setItems(list.toArray(new String[0]), (dialog, which) -> {
                        if (which == 0) showManualHotspotDialog();
                        else connectToHotspot(list.get(which), DEFAULT_HOTSPOT_PASSWORD);
                    })
                    .show();
        } catch (Exception e) {
            setBusy(false, "WiFi scan greska: " + cleanError(e));
            showManualHotspotDialog();
        }
    }

    private void showNoWifiNetworksDialog() {
        new AlertDialog.Builder(this)
                .setTitle("No WiFi networks")
                .setMessage("Huawei nije vratio listu mreza aplikaciji. Mozes otvoriti WiFi settings, povezati se na server hotspot, pa se vratiti u app i kliknuti Send file.")
                .setNegativeButton("Manual SSID", (dialog, which) -> showManualHotspotDialog())
                .setPositiveButton("Open WiFi", (dialog, which) -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)))
                .show();
    }

    private void showManualHotspotDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        EditText ssid = new EditText(this);
        ssid.setHint("SSID");
        ssid.setSingleLine(true);
        ssid.setText(DEFAULT_HOTSPOT_SSID);
        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setSingleLine(true);
        pass.setText(DEFAULT_HOTSPOT_PASSWORD);
        box.addView(ssid, new LinearLayout.LayoutParams(-1, dp(48)));
        box.addView(pass, new LinearLayout.LayoutParams(-1, dp(48)));
        new AlertDialog.Builder(this)
                .setTitle("Manual server")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> connectToHotspot(ssid.getText().toString().trim(), pass.getText().toString()))
                .show();
    }

    private void showHotspotPasswordDialog(String ssid) {
        EditText pass = new EditText(this);
        pass.setSingleLine(true);
        pass.setHint("Password za " + ssid);
        new AlertDialog.Builder(this)
                .setTitle(ssid)
                .setView(pass)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> connectToHotspot(ssid, pass.getText().toString()))
                .show();
    }

    private void connectToHotspot(String ssid, String password) {
        if (ssid.length() == 0) {
            toast("SSID je prazan.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            connectToHotspotModern(ssid, password);
        } else {
            connectToHotspotLegacy(ssid, password);
        }
    }

    private void connectToHotspotModern(String ssid, String password) {
        releasePhoneNetwork();
        WifiNetworkSpecifier.Builder specBuilder = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (password.length() >= 8) specBuilder.setWpa2Passphrase(password);
        WifiNetworkSpecifier specifier = specBuilder.build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            toast("Network manager nije dostupan.");
            return;
        }
        setBusy(true, "Android ce traziti potvrdu za WiFi hotspot...");
        phoneNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (Build.VERSION.SDK_INT >= 23) cm.bindProcessToNetwork(network);
                setBusy(false, "Povezan na hotspot. Skeniram telefon...");
                new Handler(Looper.getMainLooper()).postDelayed(() -> scanPhones(), 1500);
            }

            @Override
            public void onUnavailable() {
                setBusy(false, "Nije uspelo povezivanje na hotspot.");
            }
        };
        cm.requestNetwork(request, phoneNetworkCallback);
    }

    private void connectToHotspotLegacy(String ssid, String password) {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) throw new RuntimeException("WiFi nije dostupan.");
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = quoteWifiValue(ssid);
            if (password.length() >= 8) config.preSharedKey = quoteWifiValue(password);
            else config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            int id = wm.addNetwork(config);
            if (id < 0) throw new RuntimeException("Android nije prihvatio WiFi konfiguraciju.");
            wm.disconnect();
            wm.enableNetwork(id, true);
            wm.reconnect();
            setBusy(false, "Povezujem se na hotspot. Posle toga idi Phone > Scan phones.");
        } catch (Exception e) {
            setBusy(false, "Hotspot connect greska: " + cleanError(e));
        }
    }

    private void releasePhoneNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cm != null && phoneNetworkCallback != null) cm.unregisterNetworkCallback(phoneNetworkCallback);
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 23 && cm != null) cm.bindProcessToNetwork(null);
        phoneNetworkCallback = null;
    }

    private String cleanWifiValue(String value) {
        if (value == null) return "";
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        return value;
    }

    private String quoteWifiValue(String value) {
        String cleaned = cleanWifiValue(value);
        return "\"" + cleaned.replace("\"", "") + "\"";
    }

    private void startPhoneReceiver() {
        if (phoneServerRunning) {
            toast("Phone receiver vec radi.");
            return;
        }
        phoneServerRunning = true;
        io.execute(() -> {
            try (ServerSocket server = new ServerSocket(PHONE_PORT)) {
                phoneServerSocket = server;
                String ip = getWifiIpAddress();
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                showStorage("Phone " + ip, dir.getFreeSpace(), dir.getTotalSpace());
                setBusy(false, "Phone receiver radi: http://" + ip + ":" + PHONE_PORT);
                while (phoneServerRunning) {
                    Socket client = server.accept();
                    io.execute(() -> handlePhoneClient(client));
                }
            } catch (Exception e) {
                if (phoneServerRunning) setBusy(false, "Phone receiver greska: " + cleanError(e));
            } finally {
                phoneServerRunning = false;
                phoneServerSocket = null;
            }
        });
    }

    private void stopPhoneReceiver() {
        phoneServerRunning = false;
        try {
            if (phoneServerSocket != null) phoneServerSocket.close();
        } catch (Exception ignored) {
        }
        phoneServerSocket = null;
        runOnUiThread(() -> setBusy(false, "Phone receiver zaustavljen."));
    }

    private void handlePhoneClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(30000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String headers = readHttpHeaders(in);
            if (headers.startsWith("GET /ping")) {
                writeHttp(out, "200 OK", "LAN File Share phone receiver");
                return;
            }
            if (headers.startsWith("GET /storage")) {
                java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                writeHttp(out, "200 OK", "free=" + dir.getFreeSpace() + "\ntotal=" + dir.getTotalSpace());
                return;
            }
            if (!headers.startsWith("POST /upload")) {
                writeHttp(out, "404 Not Found", "Not found");
                return;
            }
            int length = headerInt(headers, "Content-Length");
            String encodedName = headerValue(headers, "X-File-Name");
            String name = encodedName.length() == 0 ? "phone-file.bin" : URLDecoder.decode(encodedName, "UTF-8");
            name = safeFileName(name);
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            java.io.File outFile = uniqueFile(dir, name);
            try (OutputStream fileOut = new FileOutputStream(outFile)) {
                copyFixed(in, fileOut, length);
            }
            writeHttp(out, "200 OK", "OK");
            setBusy(false, "Primljen fajl: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            setBusy(false, "Phone receive greska: " + cleanError(e));
        }
    }

    private void scanPhones() {
        String prefix = getWifiPrefix();
        if (prefix == null) {
            setBusy(false, "Nisam nasao Wi-Fi IP adresu.");
            return;
        }
        setBusy(true, "Trazim telefone sa ukljucenim receiver-om...");
        final int[] left = {254};
        final AtomicBoolean found = new AtomicBoolean(false);
        for (int i = 1; i <= 254; i++) {
            String host = prefix + i;
            io.execute(() -> {
                try {
                    if (isPhoneReceiver(host)) {
                        if (found.compareAndSet(false, true)) {
                            phoneTargetHost = host;
                            runOnUiThread(() -> ipInput.setText(host));
                            updatePhoneStorageAsync(host);
                            setBusy(false, "Nadjen telefon: " + host + ". Klikni Phone > Send file.");
                        }
                    }
                } finally {
                    synchronized (left) {
                        left[0]--;
                        if (left[0] == 0 && !found.get()) setBusy(false, "Nisam nasao drugi telefon. Na njemu ukljuci Phone > Start receiver.");
                    }
                }
            });
        }
    }

    private boolean isPhoneReceiver(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, PHONE_PORT), 250);
            socket.setSoTimeout(500);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("GET /ping HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            String response = readHttpHeaders(in);
            return response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200");
        } catch (Exception e) {
            return false;
        }
    }

    private void updatePhoneStorageAsync(String host) {
        io.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, PHONE_PORT), 800);
                socket.setSoTimeout(1200);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                out.write(("GET /storage HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                String response = readAllText(in);
                if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) return;
                long free = parseLongValue(response, "free=");
                long total = parseLongValue(response, "total=");
                showStorage("Phone " + host, free, total);
            } catch (Exception ignored) {
            }
        });
    }

    private void pickPhoneFile() {
        if (phoneTargetHost.length() == 0) {
            toast("Upisi IP drugog telefona ili prvo skeniraj.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_PHONE_FILE);
    }

    private void sendFileToPhone(Uri uri) {
        String name = getName(uri);
        String host = phoneTargetHost;
        io.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, PHONE_PORT), 5000);
                socket.setSoTimeout(60000);
                long size = querySize(uri);
                if (size < 0) throw new RuntimeException("Ne znam velicinu fajla.");
                TransferProgress transfer = beginTransfer("Saljem na telefon " + host + ": " + name, size);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                String header = "POST /upload HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Connection: close\r\n"
                        + "X-File-Name: " + URLEncoder.encode(name, "UTF-8") + "\r\n"
                        + "Content-Length: " + size + "\r\n\r\n";
                out.write(header.getBytes("UTF-8"));
                try (InputStream fileIn = getContentResolver().openInputStream(uri)) {
                    if (fileIn != null) copyStream(fileIn, out, transfer);
                }
                out.flush();
                String response = readHttpHeaders(in);
                if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) throw new RuntimeException("Telefon nije prihvatio fajl.");
                hideTransferProgress();
                setBusy(false, "Poslato na telefon: " + name);
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Phone send greska: " + cleanError(e));
            }
        });
    }

    private void sendLocalFileToPhone(java.io.File file) {
        String host = phoneTargetHost;
        io.execute(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, PHONE_PORT), 5000);
                socket.setSoTimeout(60000);
                long size = file.length();
                TransferProgress transfer = beginTransfer("Saljem na telefon " + host + ": " + file.getName(), size);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                String header = "POST /upload HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Connection: close\r\n"
                        + "X-File-Name: " + URLEncoder.encode(file.getName(), "UTF-8") + "\r\n"
                        + "Content-Length: " + size + "\r\n\r\n";
                out.write(header.getBytes("UTF-8"));
                try (InputStream fileIn = new FileInputStream(file)) {
                    copyStream(fileIn, out, transfer);
                }
                out.flush();
                String response = readHttpHeaders(in);
                if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) throw new RuntimeException("Telefon nije prihvatio fajl.");
                hideTransferProgress();
                setBusy(false, "Poslato na telefon: " + file.getName());
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Phone copy greska: " + cleanError(e));
            }
        });
    }

    private void upload(Uri uri) {
        String name = getName(uri);
        io.execute(() -> {
            String target = currentPath.length() == 0 ? name : currentPath + "/" + name;
            try {
                long size = querySize(uri);
                SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, target), cifsContext);
                TransferProgress transfer = beginTransfer("Saljem " + name, size);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream os = remote.getOutputStream()) {
                    if (in != null) copyStream(in, os, transfer);
                }
                hideTransferProgress();
                runOnUiThread(this::listFiles);
                setBusy(false, "Upload zavrsen: " + name);
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Upload greska: " + cleanError(e));
            }
        });
    }

    private void takePhoto() {
        if (cifsContext == null || currentShare.length() == 0) {
            toast("Prvo otvori PC share/folder gde zelis da snimis sliku.");
            return;
        }
        try {
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            String name = "IMG_" + System.currentTimeMillis() + ".jpg";
            pendingPhotoFile = new java.io.File(dir, name);
            Uri photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pendingPhotoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, TAKE_PHOTO);
        } catch (Exception e) {
            toast("Camera greska: " + cleanError(e));
        }
    }

    private void uploadLocalFile(java.io.File file) {
        io.execute(() -> {
            String target = currentPath.length() == 0 ? file.getName() : currentPath + "/" + file.getName();
            try {
                SmbFile remote = new SmbFile(smbUrl(currentHost, currentShare, target), cifsContext);
                TransferProgress transfer = beginTransfer("Saljem sliku " + file.getName(), file.length());
                try (InputStream in = new FileInputStream(file); OutputStream os = remote.getOutputStream()) {
                    copyStream(in, os, transfer);
                }
                hideTransferProgress();
                runOnUiThread(this::listFiles);
                setBusy(false, "Slika snimljena na PC: " + file.getName());
            } catch (Exception e) {
                hideTransferProgress();
                setBusy(false, "Upload slike greska: " + cleanError(e));
            }
        });
    }

    private String getName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) return c.getString(ix);
            }
        } catch (Exception ignored) {
        }
        return "upload.bin";
    }

    private void closeSmb() {
        cifsContext = null;
        currentShare = "";
        currentHost = "";
        currentPath = "";
    }

    private String smbUrl(String host, String share, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("smb://").append(host).append("/").append(encodePart(share)).append("/");
        if (path != null && path.length() > 0) {
            String[] parts = path.split("/");
            for (String part : parts) {
                if (part.length() > 0) sb.append(encodePart(part)).append("/");
            }
        }
        return sb.toString();
    }

    private String encodePart(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private void updatePcStorageAsync(String host, String shareName, String path) {
        if (host == null || host.length() == 0 || shareName == null || shareName.length() == 0 || cifsContext == null) return;
        io.execute(() -> {
            try {
                SmbFile folder = new SmbFile(smbUrl(host, shareName, path), cifsContext);
                showStorage("PC //" + host + "/" + shareName, folder.getDiskFreeSpace(), -1);
            } catch (Exception ignored) {
            }
        });
    }

    private void showStorage(String label, long freeBytes, long totalBytes) {
        String text;
        if (freeBytes < 0) {
            text = label + "  Free: unknown";
        } else if (totalBytes > 0) {
            text = label + "  Free: " + formatStorageBytes(freeBytes) + " / " + formatStorageBytes(totalBytes);
        } else {
            text = label + "  Free: " + formatStorageBytes(freeBytes);
        }
        runOnUiThread(() -> {
            storageLabel.setText(text);
            storageLabel.setVisibility(View.VISIBLE);
        });
    }

    private String formatStorageBytes(long bytes) {
        if (bytes < 0) return "unknown";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / 1024.0 / 1024.0;
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb);
        long kb = Math.max(0, bytes / 1024);
        return kb + " KB";
    }

    private String cleanError(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private void setBusy(boolean busy, String msg) {
        runOnUiThread(() -> {
            if (busy) transferOverlay.setVisibility(View.GONE);
            progress.setVisibility(busy ? View.VISIBLE : View.GONE);
            status.setText(msg);
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private class TransferProgress {
        final String label;
        final long totalBytes;
        long copiedBytes = 0;
        long lastUiAt = 0;

        TransferProgress(String label, long totalBytes) {
            this.label = label;
            this.totalBytes = totalBytes;
        }

        void add(long bytes) {
            copiedBytes += bytes;
            long now = System.currentTimeMillis();
            if (now - lastUiAt > 120 || copiedBytes >= totalBytes) {
                lastUiAt = now;
                updateTransferProgress(this);
            }
        }
    }

    private static class TransferProgressView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private int progress = 0;

        TransferProgressView(Context context) {
            super(context);
            trackPaint.setColor(Color.rgb(229, 229, 234));
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            ringPaint.setColor(Color.rgb(0, 122, 255));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setProgress(int value) {
            progress = Math.max(0, Math.min(100, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float stroke = Math.max(8f, getWidth() * 0.08f);
            trackPaint.setStrokeWidth(stroke);
            ringPaint.setStrokeWidth(stroke);
            float pad = stroke / 2f + 2f;
            arc.set(pad, pad, getWidth() - pad, getHeight() - pad);
            canvas.drawArc(arc, 0, 360, false, trackPaint);
            canvas.drawArc(arc, -90, progress * 3.6f, false, ringPaint);
        }
    }

    private static class Device {
        final String host;
        Device(String host) { this.host = host; }
    }

    private static class CopySelection {
        final boolean local;
        final boolean dir;
        final String name;
        final String localPath;
        final String remoteHost;
        final String remoteShare;
        final String remotePath;

        private CopySelection(boolean local, boolean dir, String name, String localPath, String remoteHost, String remoteShare, String remotePath) {
            this.local = local;
            this.dir = dir;
            this.name = name;
            this.localPath = localPath;
            this.remoteHost = remoteHost;
            this.remoteShare = remoteShare;
            this.remotePath = remotePath;
        }

        static CopySelection local(java.io.File file) {
            return new CopySelection(true, file.isDirectory(), file.getName(), file.getAbsolutePath(), "", "", "");
        }

        static CopySelection remote(String host, String share, String path, String name, boolean dir) {
            return new CopySelection(false, dir, name, "", host, share, path);
        }
    }

    private static class RemoteItem {
        final boolean dir;
        final String name;
        final String path;
        final long sizeBytes;
        final long modified;
        final boolean shareRoot;

        RemoteItem(boolean dir, String name, String path, long sizeBytes, long modified, boolean shareRoot) {
            this.dir = dir;
            this.name = name;
            this.path = path;
            this.sizeBytes = sizeBytes;
            this.modified = modified;
            this.shareRoot = shareRoot;
        }

        String label() {
            if (shareRoot) return "\uD83D\uDCC1  " + name;
            if (dir) return "\uD83D\uDCC1  " + name;
            return fileIcon(name) + "  " + name + "    " + sizeKb(sizeBytes);
        }

        String fileIcon(String fileName) {
            String lower = fileName.toLowerCase(Locale.US);
            if (endsWithAny(lower, ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")) return "\uD83D\uDDBC";
            if (endsWithAny(lower, ".mp4", ".mkv", ".avi", ".mov", ".webm", ".3gp")) return "\uD83C\uDFAC";
            if (endsWithAny(lower, ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a")) return "\uD83C\uDFB5";
            if (endsWithAny(lower, ".pdf")) return "\uD83D\uDCD5";
            if (endsWithAny(lower, ".doc", ".docx", ".rtf")) return "\uD83D\uDCDD";
            if (endsWithAny(lower, ".xls", ".xlsx", ".csv")) return "\uD83D\uDCCA";
            if (endsWithAny(lower, ".ppt", ".pptx")) return "\uD83D\uDCC8";
            if (endsWithAny(lower, ".zip", ".rar", ".7z", ".tar", ".gz")) return "\uD83D\uDCE6";
            if (endsWithAny(lower, ".apk", ".apks", ".xapk")) return "\uD83D\uDCF1";
            if (endsWithAny(lower, ".txt", ".log", ".md", ".json", ".xml", ".html", ".css", ".js", ".java", ".kt")) return "\uD83D\uDCC3";
            return "\uD83D\uDCC4";
        }

        boolean endsWithAny(String value, String... suffixes) {
            for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
            return false;
        }

        String sizeKb(long bytes) {
            long kb = Math.max(1, (bytes + 1023) / 1024);
            if (kb <= 1000) return String.format(Locale.US, "%,d KB", kb);
            double mb = bytes / 1024.0 / 1024.0;
            return String.format(Locale.US, "%.1f MB", mb);
        }
    }
}
