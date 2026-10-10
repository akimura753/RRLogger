package jp.kimuakilabo.rrlogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 心拍ベルトに接続し、通知に入っている全RRを時刻つきで保存する常駐サービス。
 * 画面を消しても、カメラなど他のアプリを使っていても動き続ける。
 */
public class LoggerService extends Service {

    static final String ACTION_START = "jp.kimuakilabo.rrlogger.START";
    static final String ACTION_STOP = "jp.kimuakilabo.rrlogger.STOP";
    static final String ACTION_MARK = "jp.kimuakilabo.rrlogger.MARK";
    static final String EXTRA_ADDR = "addr";
    static final String EXTRA_NAME = "name";

    private static final UUID HRS = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    private static final UUID HRM = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATT = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL = "rec";

    /**
     * 画面側から：delayMs 後にRRの記録を自動で止めて保存する予約を入れる（研究モードの回復期用）。
     * 予約はこのサービスが持つので、カメラ画面を閉じても、画面を消しても実行される。
     */
    static void scheduleAutoStop(long delayMs, String event) {
        LoggerService s = inst;
        if (s == null || !RecState.recording) return;
        s.h.removeCallbacks(s.autoStop);
        s.autoStopEvent = event;
        RecState.autoStopMono = SystemClock.elapsedRealtimeNanos() + delayMs * 1000000L;
        s.h.postDelayed(s.autoStop, delayMs);
    }

    private String autoStopEvent = "AUTO_STOP";
    private final Runnable autoStop = new Runnable() {
        @Override public void run() {
            if (!RecState.recording || stopping) return;
            logEvent(autoStopEvent, "auto stop");
            sound(autoStopEvent, false);
            h.postDelayed(new Runnable() {          // 合図音が鳴り終わってから保存
                @Override public void run() {
                    if (RecState.recording && !stopping) stopRec();
                }
            }, 900);
        }
    };

    /** 画面側から：出来事をCSVに書き、同時に合図音を鳴らす（研究モードの合図用）。double=true で2連音。 */
    static void signal(String event, String note, boolean doubleBeep) {
        LoggerService s = inst;
        logFromUi(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), event, note);
        if (s != null) s.sound(event, doubleBeep);
    }
    static final String APP_VERSION = "1.8";
    private static volatile LoggerService inst;

    /** 画面側（カメラ画面など）から、記録中のCSVに出来事を1行書く。時刻は呼び出し側が渡す。 */
    static void logFromUi(long wallMs, long monoNs, String event, String note) {
        LoggerService s = inst;
        if (s == null) return;
        CsvLog l = s.log;
        if (l == null) return;
        try {
            l.event(wallMs, (monoNs - RecState.startMono) / 1e9, event, note);
        } catch (Exception ignored) { }
    }
    private static final int NOTIF_ID = 1;

    private Handler h;
    private PowerManager.WakeLock wakeLock;
    private volatile CsvLog log;
    private File file;
    private volatile BluetoothGatt gatt;
    private String addr = "";
    private String name = "";
    private volatile boolean stopping = false;

    private static final long FIND_MS = 12000;                    // 機器を探す時間
    private static final long WATCHDOG_NS = 45L * 1000000000L;    // これだけ無音なら接続し直す
    private BluetoothLeScanner finder;
    private boolean finding = false;
    private int directTries = 0;
    private long attemptMono = 0;      // 今の接続の試みを始めた時刻
    private boolean linkUp = false;    // 今の接続が一度でもつながったか

    private final Runnable notifTick = new Runnable() {
        @Override public void run() {
            if (!RecState.recording) return;
            // つながっているはずなのにデータが来ない状態が続いたら、接続し直す
            long ref = Math.max(attemptMono, RecState.lastNotifMono);
            if (gatt != null && !stopping && SystemClock.elapsedRealtimeNanos() - ref > WATCHDOG_NS) {
                logEvent("ERROR", "no data for 45 s - reconnecting");
                if (linkUp) RecState.reconnects++;
                connect();
            }
            updateNotification();
            h.postDelayed(this, 2000);
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            if (RecState.recording && !stopping) connect();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        h = new Handler(Looper.getMainLooper());
        T.init(this);
        inst = this;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(a)) {
            startRec(intent.getStringExtra(EXTRA_ADDR), intent.getStringExtra(EXTRA_NAME));
        } else if (ACTION_STOP.equals(a)) {
            if (RecState.recording) stopRec(); else stopSelf();
        } else if (ACTION_MARK.equals(a)) {
            if (RecState.recording) mark(); else stopSelf();
        } else if (!RecState.recording) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (RecState.recording) finish(false);
        if (inst == this) inst = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 開始・停止

    private void startRec(String address, String devName) {
        if (RecState.recording) return;
        addr = address == null ? "" : address;
        name = devName == null ? "" : devName;
        stopping = false;
        RecState.resetForStart();
        RecState.devAddr = addr;
        RecState.devName = name;
        RecState.conn = "接続中…";

        try {
            ensureChannel();
            startForeground(NOTIF_ID, buildNotification(T.conn("接続中…")),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Exception e) {
            RecState.error = T.t("常駐を開始できません: ", "Cannot start the background service: ") + e.getClass().getSimpleName();
            RecState.conn = "停止中";
            stopSelf();
            return;
        }

        long wall = System.currentTimeMillis();
        long mono = SystemClock.elapsedRealtimeNanos();
        try {
            Exporter.exportLeftovers(this, null);
            String fn = "RR_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(wall)) + ".csv";
            RecState.activeFile = fn;
            file = new File(Exporter.recDir(this), fn);
            log = new CsvLog(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)));
            log.event(wall, 0.0, "START", "device=" + name + " addr=" + addr
                    + " phone=" + Build.MODEL + " android=" + Build.VERSION.SDK_INT + " app=" + APP_VERSION);
            RecState.fileName = fn;
        } catch (Exception e) {
            RecState.error = T.t("ファイルを作れません: ", "Cannot create the file: ") + e.getMessage();
            RecState.conn = "停止中";
            RecState.activeFile = "";
            log = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        RecState.startWall = wall;
        RecState.startMono = mono;
        RecState.recording = true;

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rrlogger:rec");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(12L * 60 * 60 * 1000);
        } catch (Exception ignored) { }

        try {
            beeper = new Beeper(h);      // 合図音を先に用意しておく
        } catch (Exception ignored) { }

        connect();
        h.postDelayed(notifTick, 2000);
    }

    private void stopRec() {
        finish(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** 記録を閉じてファイルを書き出す。 */
    private void finish(boolean byUser) {
        stopping = true;
        h.removeCallbacksAndMessages(null);
        CsvLog l = log;
        log = null;
        if (l != null) {
            try {
                l.event(System.currentTimeMillis(), elapsed(), "STOP",
                        (byUser ? "user" : "service_destroyed") + " beats=" + l.beats());
            } catch (Exception ignored) { }
            try { l.close(); } catch (Exception ignored) { }
        }
        stopFind();
        closeGatt();
        RecState.recording = false;
        RecState.autoStopMono = 0;
        RecState.conn = "停止中";
        RecState.activeFile = "";
        Uri uri = Exporter.export(this, file);
        if (uri != null) {
            RecState.lastSaved = T.t("ダウンロード/", "Download/") + Exporter.SUBDIR + "/" + file.getName();
            RecState.lastSavedUri = uri.toString();
        } else if (file != null) {
            RecState.error = T.t("書き出しに失敗しました。アプリを開き直すと再試行します。", "Export failed. It will be retried when the app is reopened.");
        }
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
            wakeLock = null;
        }
        if (beeper != null) {
            beeper.release();
            beeper = null;
        }
    }

    private void mark() {
        CsvLog l = log;
        if (l == null) return;
        int n = ++RecState.marks;
        boolean beep = getSharedPreferences(Exporter.PREFS, MODE_PRIVATE).getBoolean("beep", true);
        try {
            l.event(System.currentTimeMillis(), elapsed(), "MARK", n + (beep ? " beep" : ""));
        } catch (Exception ignored) { }
        if (beep) sound("MARK", false);
        updateNotification();
    }

    /** 動画の音声にも残る短い合図音。動画と記録の時刻合わせ用。 */
    private Beeper beeper;

    /**
     * 合図音を鳴らし、音が実際に出た時刻を「出来事名_SOUND」の行としてCSVに残す。
     * 用意した音が使えないときは従来の音で代用する（その場合、鳴るまでに約0.3秒かかる）。
     */
    private void sound(final String event, boolean doubleBeep) {
        boolean ok = false;
        try {
            if (beeper == null) beeper = new Beeper(h);
            ok = beeper.play(doubleBeep, new Beeper.Listener() {
                @Override public void onSound(long startMono) {
                    long nowMono = SystemClock.elapsedRealtimeNanos();
                    long nowWall = System.currentTimeMillis();
                    if (startMono > 0) {
                        logFromUi(nowWall - (nowMono - startMono) / 1000000L, startMono, event + "_SOUND",
                                "src=audiotrack");
                    } else {
                        logFromUi(nowWall, nowMono, event + "_SOUND", "time_unknown");
                    }
                }
            });
        } catch (Exception ignored) { }
        if (!ok) {
            logEvent(event + "_SOUND", "fallback_tone time_unknown");
            tone(doubleBeep ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP, doubleBeep ? 500 : 300);
        }
    }

    private void tone(int type, int ms) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            tg.startTone(type, ms);
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    try { tg.release(); } catch (Exception ignored) { }
                }
            }, ms + 400);
        } catch (Exception ignored) { }
    }

    private double elapsed() {
        return (SystemClock.elapsedRealtimeNanos() - RecState.startMono) / 1e9;
    }

    private void logEvent(String ev, String note) {
        CsvLog l = log;
        if (l == null) return;
        try { l.event(System.currentTimeMillis(), elapsed(), ev, note); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ Bluetooth

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    /**
     * 接続の試み1回ぶん。まず機器の電波を探し、見つけた相手に接続する。
     * （保存した番号だけで直接つなぐと、Bluetoothを入れ直した後などに失敗することがあるため）
     * 一定時間見つからなければ、番号だけで直接つなぐ方法も試す。
     */
    private void connect() {
        if (stopping || !RecState.recording) return;
        h.removeCallbacks(reconnect);
        stopFind();
        closeGatt();
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            RecState.conn = "Bluetoothがオフです";
            h.postDelayed(reconnect, 3000);
            return;
        }
        attemptMono = SystemClock.elapsedRealtimeNanos();
        linkUp = false;
        try {
            finder = ad.getBluetoothLeScanner();
            if (finder == null) throw new IllegalStateException("no scanner");
            List<ScanFilter> fl = new ArrayList<ScanFilter>();
            fl.add(new ScanFilter.Builder().setDeviceAddress(addr).build());
            fl.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(HRS)).build());
            ScanSettings st = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            finder.startScan(fl, st, findCb);
            finding = true;
            RecState.conn = "機器を探しています…";
            h.postDelayed(findTimeout, FIND_MS);
        } catch (Exception e) {
            finding = false;
            connectDirect();
        }
    }

    private final ScanCallback findCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, final ScanResult r) {
            if (r == null || r.getDevice() == null) return;
            if (!addr.equalsIgnoreCase(r.getDevice().getAddress())) return;
            h.post(new Runnable() {
                @Override public void run() {
                    if (!finding) return;
                    stopFind();
                    connectTo(r.getDevice());
                }
            });
        }

        @Override public void onScanFailed(final int errorCode) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (!finding) return;
                    finding = false;
                    h.removeCallbacks(findTimeout);
                    logEvent("ERROR", "scan failed code=" + errorCode);
                    connectDirect();
                }
            });
        }
    };

    private final Runnable findTimeout = new Runnable() {
        @Override public void run() {
            if (!finding) return;
            stopFind();
            connectDirect();
        }
    };

    private void stopFind() {
        h.removeCallbacks(findTimeout);
        if (!finding) return;
        finding = false;
        try {
            if (finder != null) finder.stopScan(findCb);
        } catch (Exception ignored) { }
    }

    /** 番号だけで直接つなぐ（見つからなかったときの予備）。 */
    private void connectDirect() {
        if (stopping || !RecState.recording) return;
        try {
            BluetoothAdapter ad = adapter();
            if (ad == null || !ad.isEnabled()) {
                RecState.conn = "Bluetoothがオフです";
                h.postDelayed(reconnect, 3000);
                return;
            }
            BluetoothDevice d;
            if (Build.VERSION.SDK_INT >= 33 && (directTries++ % 2) == 1) {
                d = ad.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM);
            } else {
                d = ad.getRemoteDevice(addr);
            }
            connectTo(d);
        } catch (SecurityException e) {
            RecState.conn = "Bluetoothの権限がありません";
            RecState.error = T.t("Bluetoothの権限がありません", "No Bluetooth permission");
            logEvent("ERROR", "SecurityException on connect");
        } catch (Exception e) {
            RecState.conn = "接続エラー";
            logEvent("ERROR", "connect " + e.getClass().getSimpleName());
            h.postDelayed(reconnect, 3000);
        }
    }

    private void connectTo(BluetoothDevice d) {
        if (stopping || !RecState.recording) return;
        try {
            closeGatt();
            RecState.conn = "接続中…";
            attemptMono = SystemClock.elapsedRealtimeNanos();
            linkUp = false;
            gatt = d.connectGatt(this, false, new Cb(), BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                RecState.conn = "接続を開始できません";
                h.postDelayed(reconnect, 3000);
            }
        } catch (SecurityException e) {
            RecState.conn = "Bluetoothの権限がありません";
            RecState.error = T.t("Bluetoothの権限がありません", "No Bluetooth permission");
            logEvent("ERROR", "SecurityException on connect");
        } catch (Exception e) {
            RecState.conn = "接続エラー";
            logEvent("ERROR", "connect " + e.getClass().getSimpleName());
            h.postDelayed(reconnect, 3000);
        }
    }

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) { }
            try { g.close(); } catch (Exception ignored) { }
        }
    }

    private final class Cb extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) {                 // 古い接続の通知
                        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            try { g.close(); } catch (Exception ignored) { }
                        }
                        return;
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        linkUp = true;
                        RecState.conn = "接続（準備中）";
                        logEvent("CONNECT", "status=" + status);
                        h.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (g != gatt) return;
                                try {
                                    if (!g.discoverServices()) logEvent("ERROR", "discoverServices=false");
                                } catch (SecurityException e) {
                                    logEvent("ERROR", "SecurityException on discover");
                                }
                            }
                        }, 300);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        logEvent("DISCONNECT", "status=" + status);
                        try { g.close(); } catch (Exception ignored) { }
                        gatt = null;
                        if (RecState.recording && !stopping) {
                            if (linkUp) RecState.reconnects++;
                            linkUp = false;
                            RecState.conn = "切断 → 再接続待ち";
                            h.removeCallbacks(reconnect);
                            h.postDelayed(reconnect, 2000);
                        }
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt) return;
            try {
                BluetoothGattService s = g.getService(HRS);
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(HRM);
                if (c == null) {
                    if (status == BluetoothGatt.GATT_SUCCESS) RecState.conn = "この機器には心拍サービスがありません";
                    logEvent("ERROR", "no heart rate service status=" + status);
                    return;
                }
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                if (d == null) {
                    logEvent("ERROR", "no CCCD");
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    legacyWriteDescriptor(g, d);
                }
            } catch (SecurityException e) {
                logEvent("ERROR", "SecurityException on enable");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (g != gatt) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                RecState.conn = "記録中";
                logEvent("NOTIFY_ON", "");
            } else {
                logEvent("ERROR", "descriptor write status=" + status);
            }
            try {                                   // 電池残量を1回読む
                BluetoothGattService bs = g.getService(BAS);
                BluetoothGattCharacteristic bc = bs == null ? null : bs.getCharacteristic(BATT);
                if (bc != null) g.readCharacteristic(bc);
            } catch (Exception ignored) { }
        }

        // Android 13 以上
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (HRM.equals(c.getUuid())) onHeartRate(value);
        }

        // Android 12 以下
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33 && HRM.equals(c.getUuid())) onHeartRate(c.getValue());
        }

        // Android 13 以上
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && BATT.equals(c.getUuid())) onBattery(value);
        }

        // Android 12 以下
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS && BATT.equals(c.getUuid())) {
                onBattery(c.getValue());
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void legacyWriteDescriptor(BluetoothGatt g, BluetoothGattDescriptor d) {
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        g.writeDescriptor(d);
    }

    private void onBattery(byte[] v) {
        if (v == null || v.length < 1) return;
        int pct = v[0] & 0xFF;
        RecState.battery = pct;
        logEvent("BATTERY", String.valueOf(pct));
    }

    /** 心拍通知1回ぶん。受信時刻はスマホの時計（動画と同じ時計）。 */
    private void onHeartRate(byte[] value) {
        long wall = System.currentTimeMillis();
        long mono = SystemClock.elapsedRealtimeNanos();
        CsvLog l = log;
        if (l == null || value == null) return;
        HrParser.Packet p = HrParser.parse(value);
        try {
            l.packet(wall, (mono - RecState.startMono) / 1e9, p);
        } catch (Exception e) {
            if (!stopping) RecState.error = T.t("書き込みエラー: ", "Write error: ") + e.getMessage();
            return;
        }
        long sum = 0;
        for (int r : p.rr) sum += r;
        if (RecState.firstNotifMono == 0) {
            RecState.firstNotifMono = mono;
        } else {
            RecState.sumRawAfterFirst += sum;
        }
        RecState.lastNotifMono = mono;
        RecState.notifs++;
        RecState.beats += p.rr.length;
        RecState.hr = p.hr;
        RecState.contact = p.contact;
        if (p.rr.length > 0) {
            RecState.lastRrRaw = p.rr[p.rr.length - 1];
            RecState.lastRrMono = mono;
        }
        if (!"記録中".equals(RecState.conn)) RecState.conn = "記録中";
    }

    // ------------------------------------------------------------------ 通知

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, T.t("記録中の表示", "Recording status"), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), fl);
        PendingIntent markPi = PendingIntent.getService(this, 1,
                new Intent(this, LoggerService.class).setAction(ACTION_MARK), fl);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(T.t("RRロガー", "RR Logger"))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((Icon) null, T.t("マーカー", "Marker"), markPi).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotification() {
        try {
            long sec = (SystemClock.elapsedRealtimeNanos() - RecState.startMono) / 1000000000L;
            String t = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
            String hr = RecState.hr >= 0 ? RecState.hr + " bpm" : "-- bpm";
            String text = T.conn(RecState.conn) + "  " + hr + "  " + RecState.beats + T.t("拍  ", " beats  ") + t
                    + (RecState.marks > 0 ? T.t("  印", "  mk") + RecState.marks : "");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }
}
