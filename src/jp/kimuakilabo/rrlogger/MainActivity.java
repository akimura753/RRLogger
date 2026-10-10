package jp.kimuakilabo.rrlogger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 画面：機器を探す → 記録開始 → マーカー → 停止 → 共有。記録そのものは LoggerService が行う。 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final ParcelUuid HRS_UUID =
            ParcelUuid.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_MS = 15000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView connView, hrView, detailView, msgView, scanInfo;
    private Button scanBtn, lastBtn, stopBtn, markBtn, shareBtn;
    private CheckBox showAllBox, beepBox;
    private LinearLayout devList, idleBox, recBox;

    private Runnable pendingAfterPerms;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private String listSignature = "";
    private String notice = "";

    private static final class Found {
        String addr; String name; int rssi; boolean hrs;
    }
    private final LinkedHashMap<String, Found> found = new LinkedHashMap<String, Found>();

    // ------------------------------------------------------------------ 画面の組み立て

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.rgb(0x20, 0x20, 0x20));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, int heightDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setMinHeight(dp(heightDp));
        return b;
    }

    private static LinearLayout.LayoutParams row(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Exporter.PREFS, MODE_PRIVATE);
        T.init(this);
        setTitle(T.t("RRロガー", "RR Logger"));

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        connView = text(20, true);
        root.addView(connView, row(0));

        hrView = text(64, true);
        hrView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hrView, row(dp(4)));

        detailView = text(15, false);
        detailView.setTypeface(Typeface.MONOSPACE);
        detailView.setLineSpacing(dp(2), 1f);
        root.addView(detailView, row(dp(4)));

        msgView = text(15, true);
        msgView.setTextColor(Color.rgb(0xB0, 0x20, 0x20));
        root.addView(msgView, row(dp(8)));

        // ---- 記録中に見せる部分
        recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(recBox, row(dp(8)));

        markBtn = button(T.t("マーカー（合図）を入れる", "Add marker (signal)"), 88);
        markBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, LoggerService.class)
                        .setAction(LoggerService.ACTION_MARK));
            }
        });
        recBox.addView(markBtn, row(0));

        Button camBtn = button(T.t("動画を撮る（心拍を重ねて表示）", "Record video (heart rate overlay)"), 72);
        camBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CameraActivity.class));
            }
        });
        recBox.addView(camBtn, row(dp(8)));

        TextView recHint = text(14, false);
        recHint.setText(T.t("ホームに戻って、いつものカメラで撮影することもできます。画面を消しても記録は続きます。", "You can also go Home and film with the normal camera. Recording continues with the screen off. ")
                + T.t("通知の「マーカー」でも合図を入れられます。", "Markers can also be added from the notification."));
        recBox.addView(recHint, row(dp(8)));

        stopBtn = button(T.t("記録を停止して保存", "Stop and save"), 64);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmStop(); }
        });
        recBox.addView(stopBtn, row(dp(32)));

        // ---- 停止中に見せる部分
        idleBox = new LinearLayout(this);
        idleBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(idleBox, row(dp(8)));

        lastBtn = button(T.t("前回の機器で記録開始", "Start with last device"), 72);
        lastBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String a = prefs.getString("lastAddr", "");
                final String n = prefs.getString("lastName", "");
                if (a.length() == 0) return;
                ensureReady(new Runnable() {
                    @Override public void run() { startRec(a, n); }
                });
            }
        });
        idleBox.addView(lastBtn, row(0));

        scanBtn = button(T.t("機器を探す", "Scan for devices"), 64);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (scanning) { stopScan(); return; }
                ensureReady(new Runnable() {
                    @Override public void run() { startScan(); }
                });
            }
        });
        idleBox.addView(scanBtn, row(dp(8)));

        scanInfo = text(14, false);
        idleBox.addView(scanInfo, row(dp(4)));

        devList = new LinearLayout(this);
        devList.setOrientation(LinearLayout.VERTICAL);
        idleBox.addView(devList, row(dp(4)));

        showAllBox = new CheckBox(this);
        showAllBox.setText(T.t("心拍計以外の機器も表示する", "Also show devices that are not heart rate sensors"));
        showAllBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) { rebuildList(true); }
        });
        idleBox.addView(showAllBox, row(dp(8)));

        beepBox = new CheckBox(this);
        beepBox.setText(T.t("マーカーで合図音を鳴らす（動画との時刻合わせ用）", "Beep on marker (for syncing with video)"));
        beepBox.setChecked(prefs.getBoolean("beep", true));
        beepBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton c, boolean on) {
                prefs.edit().putBoolean("beep", on).apply();
            }
        });
        idleBox.addView(beepBox, row(0));

        shareBtn = button(T.t("最後の記録を共有", "Share last recording"), 56);
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareLast(); }
        });
        idleBox.addView(shareBtn, row(dp(16)));

        TextView where = text(13, false);
        where.setText(T.t("RRの記録は「ダウンロード／RRLogger」にCSVで、アプリ内で撮った動画は「ムービー／RRLogger」に保存されます。", "RR recordings are saved as CSV in Download/RRLogger; videos taken in the app are saved in Movies/RRLogger."));
        idleBox.addView(where, row(dp(8)));

        Button langBtn = button(T.en ? "日本語にする" : "Switch to English", 48);
        langBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                T.set(MainActivity.this, !T.en);
                recreate();
            }
        });
        idleBox.addView(langBtn, row(dp(16)));

        setContentView(sv);
    }

    // ------------------------------------------------------------------ 表示の更新

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
        if (!RecState.recording) {
            new Thread(new Runnable() {
                @Override public void run() {
                    if (RecState.recording) return;
                    final int n = Exporter.exportLeftovers(getApplicationContext(), null);
                    if (n > 0) {
                        h.post(new Runnable() {
                            @Override public void run() {
                                notice = T.t("前回保存できなかった記録 ", "Saved ") + n + T.t(" 件を「ダウンロード／RRLogger」に保存しました。", " recording(s) left over from last time to Download/RRLogger.");
                            }
                        });
                    }
                }
            }).start();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        stopScan();
    }

    private void render() {
        boolean rec = RecState.recording;
        recBox.setVisibility(rec ? View.VISIBLE : View.GONE);
        hrView.setVisibility(rec ? View.VISIBLE : View.GONE);
        idleBox.setVisibility(rec ? View.GONE : View.VISIBLE);

        StringBuilder d = new StringBuilder();
        String msg = RecState.error;
        if (rec) {
            long now = SystemClock.elapsedRealtimeNanos();
            long sec = (now - RecState.startMono) / 1000000000L;
            connView.setText("● " + T.conn(RecState.conn));
            boolean ok = "記録中".equals(RecState.conn);
            connView.setTextColor(ok ? Color.rgb(0x0A, 0x7A, 0x2A) : Color.rgb(0xB0, 0x60, 0x00));
            hrView.setText(RecState.hr >= 0 ? RecState.hr + " bpm" : "-- bpm");

            d.append(T.t("機器      ", "Device    ")).append(RecState.devName.length() > 0 ? RecState.devName : RecState.devAddr);
            if (RecState.battery >= 0) d.append(T.t("（電池 ", " (battery ")).append(RecState.battery).append(T.t("%）", "%)"));
            d.append('\n');
            d.append(String.format(Locale.US, T.t("経過      %02d:%02d:%02d\n", "Elapsed   %02d:%02d:%02d\n"), sec / 3600, (sec / 60) % 60, sec % 60));
            d.append(T.t("拍数      ", "Beats     ")).append(RecState.beats).append(T.t(" 拍（通知 ", " (")).append(RecState.notifs).append(T.t(" 回）\n", " notifications)\n"));
            d.append(T.t("直近のRR  ", "Last RR   "));
            if (RecState.lastRrRaw >= 0) {
                d.append(String.format(Locale.US, "%.0f ms", RecState.lastRrRaw * 1000.0 / 1024.0));
            } else {
                d.append("--");
            }
            d.append('\n');
            d.append(T.t("取得率    ", "Coverage  "));
            long first = RecState.firstNotifMono, last = RecState.lastNotifMono;
            if (first > 0 && last - first > 10L * 1000000000L) {
                double pct = (RecState.sumRawAfterFirst / 1024.0) / ((last - first) / 1e9) * 100.0;
                d.append(String.format(Locale.US, T.t("%.1f %%（RR合計÷経過時間）", "%.1f %% (RR sum / elapsed time)"), pct));
            } else {
                d.append(T.t("計算中…", "calculating…"));
            }
            d.append('\n');
            d.append(T.t("電極の接触 ", "Contact   ")).append(RecState.contact == 1 ? T.t("あり", "yes") : RecState.contact == 0 ? T.t("なし", "no") : T.t("不明", "unknown")).append('\n');
            d.append(T.t("マーカー  ", "Markers   ")).append(RecState.marks).append(T.t(" 回\n", "\n"));
            d.append(T.t("切断      ", "Dropouts  ")).append(RecState.reconnects).append(T.t(" 回\n", "\n"));
            d.append(T.t("ファイル  ", "File      ")).append(RecState.fileName);

            long auto = RecState.autoStopMono;
            if (auto > now) {
                long rem = (auto - now) / 1000000000L + 1;
                d.append(String.format(Locale.US, T.t("\n回復を記録中  あと %d:%02d で自動保存", "\nRecording recovery: auto-save in %d:%02d"), rem / 60, rem % 60));
            }
            if (last > 0) {
                long gap = (now - last) / 1000000000L;
                long lastRr = RecState.lastRrMono;
                if (gap >= 5) {
                    msg = T.t("データが ", "No data for ") + gap + T.t(" 秒届いていません。ベルトの装着と距離を確認してください。", " s. Check that the strap is worn and within range.");
                } else if ((lastRr == 0 && now - RecState.firstNotifMono > 5L * 1000000000L)
                        || (lastRr > 0 && now - lastRr > 5L * 1000000000L)) {
                    msg = T.t("機器にはつながっていますが、心拍（RR）が入ってきません。", "Connected, but no heartbeats (RR) are coming in. ")
                            + T.t("装着と電極の濡れを確認してください。RRを出さない機器の可能性もあります（機器: ", "Check the fit and wet the electrodes. The device may not send RR at all (device: ")
                            + RecState.devName + T.t("）。", ").");
                }
            } else if (sec >= 20) {
                msg = T.t("まだデータが届きません。ベルトを濡らして装着し、他のアプリ（Sensor Logger、phyphox など）の接続を切ってください。", "No data yet. Wet the electrodes, put the strap on, and disconnect other apps (Sensor Logger, phyphox, etc.).");
            }
        } else {
            connView.setText(T.conn("停止中"));
            connView.setTextColor(Color.rgb(0x20, 0x20, 0x20));
            hrView.setText("");
            String saved = RecState.lastSaved;
            if (saved.length() == 0) {
                String n = prefs.getString("lastSavedName", "");
                if (n.length() > 0) saved = T.t("ダウンロード/", "Download/") + Exporter.SUBDIR + "/" + n;
            }
            if (saved.length() > 0) d.append(T.t("最後の記録\n", "Last recording\n")).append(saved);
            if (RecState.lastVideo.length() > 0) d.append(T.t("\n最後の動画\n", "\nLast video\n")).append(RecState.lastVideo);
            if (RecState.beats > 0 && RecState.lastSaved.length() > 0) {
                d.append("\n").append(RecState.beats).append(T.t(" 拍、マーカー ", " beats, markers ")).append(RecState.marks).append(T.t(" 回", ""));
            }
            String la = prefs.getString("lastAddr", "");
            String ln = prefs.getString("lastName", "");
            lastBtn.setVisibility(la.length() > 0 ? View.VISIBLE : View.GONE);
            lastBtn.setText(T.t("前回の機器で記録開始\n", "Start with last device\n") + (ln.length() > 0 ? ln : la));
            boolean hasLast = RecState.lastSavedUri.length() > 0 || prefs.getString("lastSavedUri", "").length() > 0;
            shareBtn.setVisibility(hasLast ? View.VISIBLE : View.GONE);
            scanBtn.setText(scanning ? T.t("探すのをやめる", "Stop scanning") : T.t("機器を探す", "Scan for devices"));
            if (msg.length() == 0) msg = notice;
        }
        detailView.setText(d.toString());
        detailView.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
        msgView.setText(msg);
        msgView.setVisibility(msg.length() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ 権限とBluetooth

    private List<String> requiredPerms() {
        List<String> p = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return p;
    }

    private boolean hasRequired() {
        for (String p : requiredPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    /** 権限とBluetoothの状態を確かめてから then を実行する。 */
    private void ensureReady(Runnable then) {
        List<String> ask = new ArrayList<String>();
        for (String p : requiredPerms()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotif", false)) {
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean("askedNotif", true).apply();
        }
        if (!ask.isEmpty()) {
            pendingAfterPerms = then;
            requestPermissions(ask.toArray(new String[0]), REQ_PERMS);
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null) {
            notice = T.t("このスマホはBluetoothを使えません。", "This phone does not support Bluetooth.");
            return;
        }
        if (!ad.isEnabled()) {
            notice = T.t("Bluetoothをオンにしてから、もう一度押してください。", "Turn Bluetooth on, then press again.");
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            } catch (Exception ignored) { }
            return;
        }
        notice = "";
        RecState.error = "";
        then.run();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        Runnable then = pendingAfterPerms;
        pendingAfterPerms = null;
        if (hasRequired()) {
            if (then != null) ensureReady(then);
        } else {
            notice = Build.VERSION.SDK_INT >= 31
                    ? T.t("「付近のデバイス」の許可が必要です。設定 → アプリ → RRロガー → 権限 で許可してください。", "The \"Nearby devices\" permission is required. Allow it in Settings → Apps → RR Logger → Permissions.")
                    : T.t("「位置情報」の許可が必要です（Bluetooth機器を探すため）。設定 → アプリ → RRロガー → 権限 で許可してください。", "The \"Location\" permission is required (to scan for Bluetooth devices). Allow it in Settings → Apps → RR Logger → Permissions.");
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    // ------------------------------------------------------------------ 機器を探す

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult r) {
            if (r == null || r.getDevice() == null) return;
            String addr = r.getDevice().getAddress();
            ScanRecord sr = r.getScanRecord();
            String name = sr == null ? null : sr.getDeviceName();
            if (name == null) {
                try { name = r.getDevice().getName(); } catch (SecurityException ignored) { }
            }
            boolean hrs = false;
            if (sr != null && sr.getServiceUuids() != null) hrs = sr.getServiceUuids().contains(HRS_UUID);
            Found f = found.get(addr);
            if (f == null) {
                f = new Found();
                f.addr = addr;
                found.put(addr, f);
            }
            if (name != null && name.length() > 0) f.name = name;
            f.rssi = r.getRssi();
            f.hrs = f.hrs || hrs;
            rebuildList(false);
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            scanInfo.setText(T.t("機器を探せませんでした（コード ", "Scan failed (code ") + errorCode + T.t("）。Bluetoothを入れ直してください。", "). Turn Bluetooth off and on again."));
        }
    };

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() { stopScan(); }
    };

    private void startScan() {
        BluetoothAdapter ad = adapter();
        if (ad == null) return;
        try {
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) {
                scanInfo.setText(T.t("Bluetoothがオフです。", "Bluetooth is off."));
                return;
            }
            found.clear();
            listSignature = "";
            devList.removeAllViews();
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, st, scanCb);
            scanning = true;
            scanInfo.setText(T.t("探しています…（ベルトを装着して電極を濡らしてください）", "Scanning… (put the strap on and wet the electrodes)"));
            h.removeCallbacks(scanTimeout);
            h.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException e) {
            scanInfo.setText(T.t("Bluetoothの権限がありません。", "No Bluetooth permission."));
        }
    }

    private void stopScan() {
        h.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) { }
        if (devList.getChildCount() == 0) {
            scanInfo.setText(Build.VERSION.SDK_INT <= 30
                    ? T.t("見つかりませんでした。スマホの「位置情報」をオンにし、ベルトを装着してもう一度探してください。", "Nothing found. Turn on Location on the phone, put the strap on, and scan again.")
                    : T.t("見つかりませんでした。ベルトを装着し、他のアプリの接続を切ってから、もう一度探してください。", "Nothing found. Put the strap on, disconnect other apps, and scan again."));
        } else {
            scanInfo.setText(T.t("記録する機器を押してください。", "Tap the device to record from."));
        }
    }

    private void rebuildList(boolean force) {
        final boolean all = showAllBox.isChecked();
        List<Found> show = new ArrayList<Found>();
        for (Found f : found.values()) {
            if (f.hrs || (all && f.name != null)) show.add(f);
        }
        Collections.sort(show, new Comparator<Found>() {
            @Override public int compare(Found a, Found b) {
                if (a.hrs != b.hrs) return a.hrs ? -1 : 1;
                return a.addr.compareTo(b.addr);
            }
        });
        StringBuilder sig = new StringBuilder();
        for (Found f : show) sig.append(f.addr).append('|').append(f.name).append('|').append(f.hrs).append(';');
        if (!force && sig.toString().equals(listSignature)) return;
        listSignature = sig.toString();
        devList.removeAllViews();
        for (final Found f : show) {
            final String label = f.name != null ? f.name : f.addr;
            Button b = button((f.hrs ? "♥ " : "") + label + "\n" + f.addr + T.t("  でここから記録開始", "  — tap to start recording"), 72);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ensureReady(new Runnable() {
                        @Override public void run() { startRec(f.addr, label); }
                    });
                }
            });
            devList.addView(b, row(dp(4)));
        }
        if (scanning && !show.isEmpty()) scanInfo.setText(T.t("記録する機器を押してください。", "Tap the device to record from."));
    }

    // ------------------------------------------------------------------ 記録の開始・停止・共有

    private void startRec(String addr, String name) {
        stopScan();
        if (RecState.recording) return;
        prefs.edit().putString("lastAddr", addr).putString("lastName", name).apply();
        notice = "";
        Intent i = new Intent(this, LoggerService.class)
                .setAction(LoggerService.ACTION_START)
                .putExtra(LoggerService.EXTRA_ADDR, addr)
                .putExtra(LoggerService.EXTRA_NAME, name);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            notice = T.t("記録を開始できませんでした: ", "Could not start recording: ") + e.getClass().getSimpleName();
        }
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setMessage(T.t("記録を停止して保存しますか？", "Stop recording and save?"))
                .setPositiveButton(T.t("停止して保存", "Stop and save"), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startService(new Intent(MainActivity.this, LoggerService.class)
                                .setAction(LoggerService.ACTION_STOP));
                    }
                })
                .setNegativeButton(T.t("続ける", "Continue"), null)
                .show();
    }

    private void shareLast() {
        String u = RecState.lastSavedUri;
        if (u.length() == 0) u = prefs.getString("lastSavedUri", "");
        if (u.length() == 0) return;
        try {
            Uri uri = Uri.parse(u);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/csv");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.setClipData(ClipData.newRawUri(T.t("RR記録", "RR recording"), uri));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, T.t("記録を共有", "Share recording")));
        } catch (Exception e) {
            notice = T.t("共有できませんでした。「ダウンロード／RRLogger」から直接取り出してください。", "Could not share. Take the file directly from Download/RRLogger.");
        }
    }
}
