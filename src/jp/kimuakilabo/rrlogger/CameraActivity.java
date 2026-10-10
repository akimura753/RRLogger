package jp.kimuakilabo.rrlogger;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * アプリ内のカメラ画面。心拍とRRを重ねて表示しながら録画する。
 * 録画の開始・最初のコマ・終了の時刻を、RRと同じCSVに書き込む。
 * RRの記録そのものは LoggerService が続けている。
 */
public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private static final int REQ_PERMS = 7;
    static final String VIDEO_DIR = "RRLogger";

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private FrameLayout root;
    private SurfaceView sv;
    private TextView info, msg, recBanner, protoView;
    private Button protoBtn;

    // 研究モード: 録画開始 → 5秒後に合図 → 運動60秒 → 「60秒経過」→ レスト30秒 → 自動終了
    private static final int P_LEAD = 5, P_WORK = 60, P_REST = 30;
    private static final int P_RECOVERY = 180;      // 運動終了からRRを取り続ける時間（動画は P_REST まで）
    private boolean sawRecovery = false;
    private boolean protoPending = false;   // カメラの準備待ち
    private boolean protoArmed = false;     // 録画開始待ち
    private boolean protoActive = false;    // 進行中
    private boolean protoClosing = false;   // 終了処理中
    private long protoT0 = 0;               // 録画が始まった時刻（elapsedRealtimeNanos）
    private long protoPendingSince = 0;
    private Button recBtn, markBtn, switchBtn, backBtn;
    private final Button[] qBtn = new Button[3];

    // 画質: 0=標準 1=軽い 2=極軽（姿勢推定用。2分で約24MB）
    private static final String[] Q_NAME = {"標準", "軽い", "極軽"};
    private static final String[] Q_NAME_EN = {"Standard", "Light", "Ultra-light"};
    private static String qName(int q) { return T.en ? Q_NAME_EN[q] : Q_NAME[q]; }
    private static final int[] Q_W = {1920, 1280, 640};
    private static final int[] Q_H = {1080, 720, 360};
    private static final int[] Q_VBPS = {12000000, 3800000, 1500000};
    private static final int[] Q_ABPS = {128000, 96000, 64000};
    private int quality = 0;

    private static double mbPerMin(int q) {
        return (Q_VBPS[q] + Q_ABPS[q]) / 8.0 * 60.0 / 1e6;
    }

    private CameraManager cm;
    private String camId;
    private boolean front = false;
    private int sensorOrientation = 90;
    private boolean tsRealtime = false;
    private boolean afVideo = false;
    private Range<Integer> fpsRange;
    private Size videoSize = new Size(1920, 1080);
    private Size previewSize = new Size(1280, 720);

    private CameraDevice cam;
    private CameraCaptureSession session;
    private boolean opening = false;
    private boolean resumed = false;
    private boolean askedPerms = false;
    private int surfW = 0, surfH = 0;
    private boolean surfaceAlive = false;

    private MediaRecorder rec;
    private ParcelFileDescriptor pfd;
    private Uri videoUri;
    private String videoName = "";
    private boolean recording = false;
    private boolean busy = false;
    private boolean awaitingFirst = false;
    private boolean audioOk = false;
    private long recStartMono = 0;
    private String lastSavedVideo = "";
    private String notice = "";

    private OrientationEventListener oel;
    private int deviceDeg = 0;

    // ------------------------------------------------------------------ 画面

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(dp(64));
        return b;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(Exporter.PREFS, MODE_PRIVATE);
        T.init(this);
        front = prefs.getBoolean("camFront", false);
        quality = Math.max(0, Math.min(2, prefs.getInt("quality", 0)));
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        info = new TextView(this);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        info.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        info.setTextColor(Color.WHITE);
        info.setBackgroundColor(Color.argb(0x99, 0, 0, 0));
        info.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.addView(info, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        recBanner = new TextView(this);
        recBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        recBanner.setTypeface(Typeface.DEFAULT_BOLD);
        recBanner.setTextColor(Color.WHITE);
        recBanner.setPadding(dp(12), dp(6), dp(12), dp(6));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        blp.topMargin = dp(128);     // 左上の心拍表示と重ならない位置
        root.addView(recBanner, blp);

        protoView = new TextView(this);
        protoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        protoView.setTypeface(Typeface.DEFAULT_BOLD);
        protoView.setTextColor(Color.WHITE);
        protoView.setGravity(Gravity.CENTER);
        protoView.setBackgroundColor(Color.argb(0xAA, 0, 0, 0));
        protoView.setPadding(dp(16), dp(10), dp(16), dp(10));
        protoView.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        plp.topMargin = dp(190);
        root.addView(protoView, plp);

        msg = new TextView(this);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setTextColor(Color.WHITE);
        msg.setBackgroundColor(Color.argb(0xCC, 0x90, 0x20, 0x20));
        msg.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mlp.leftMargin = dp(16);
        mlp.rightMargin = dp(16);
        root.addView(msg, mlp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.argb(0x99, 0, 0, 0));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        recBtn = button(T.t("録画開始", "Record"));
        recBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recording) {
                    // 開始直後の二度押しで止まってしまうのを防ぐ
                    if (SystemClock.elapsedRealtimeNanos() - recStartMono < 1500000000L) return;
                    stopRecording(true);
                } else {
                    startRecording();
                }
                render();
            }
        });
        markBtn = button(T.t("マーカー", "Marker"));
        markBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(CameraActivity.this, LoggerService.class)
                        .setAction(LoggerService.ACTION_MARK));
            }
        });
        switchBtn = button(T.t("カメラ切替", "Switch cam"));
        switchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (recording || busy) return;
                front = !front;
                prefs.edit().putBoolean("camFront", front).apply();
                closeCamera();
                setupCamera();
            }
        });
        backBtn = button(T.t("戻る", "Back"));
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        LinearLayout.LayoutParams norm = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(recBtn, wide);
        bar.addView(markBtn, norm);
        bar.addView(switchBtn, norm);
        bar.addView(backBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        qRow.setBackgroundColor(Color.argb(0x99, 0, 0, 0));
        qRow.setPadding(dp(6), dp(4), dp(6), 0);
        for (int i = 0; i < 3; i++) {
            final int q = i;
            Button qb = button(qName(i));
            qb.setMinHeight(dp(48));
            qb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (recording || busy || q == quality) return;
                    quality = q;
                    prefs.edit().putInt("quality", q).apply();
                    closeCamera();
                    setupCamera();
                }
            });
            qBtn[i] = qb;
            qRow.addView(qb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        protoBtn = button(T.t("▶ 研究モード（5秒後に合図 → 運動60秒 → 回復3分。動画は95秒まで）", "▶ Research mode (signal after 5 s → 60 s exercise → 3 min recovery; video to 95 s)"));
        protoBtn.setMinHeight(dp(56));
        protoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startProto(); }
        });
        LinearLayout pRow = new LinearLayout(this);
        pRow.setOrientation(LinearLayout.HORIZONTAL);
        pRow.setBackgroundColor(Color.argb(0x99, 0, 0, 0));
        pRow.setPadding(dp(6), dp(4), dp(6), 0);
        pRow.addView(protoBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(pRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(qRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);

        oel = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceDeg = ((o + 45) / 90 * 90) % 360;
            }
        };
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            h.postDelayed(this, 250);
        }
    };

    private void render() {
        StringBuilder s = new StringBuilder();
        if (RecState.recording) {
            s.append("♥ ").append(RecState.hr >= 0 ? String.valueOf(RecState.hr) : "--").append(" bpm");
            if (RecState.lastRrRaw >= 0) {
                s.append(String.format(Locale.US, "  RR %.0f ms", RecState.lastRrRaw * 1000.0 / 1024.0));
            }
            s.append('\n');
            long first = RecState.firstNotifMono, last = RecState.lastNotifMono;
            s.append(RecState.beats).append(T.t(" 拍", " beats"));
            if (first > 0 && last - first > 10L * 1000000000L) {
                double pct = (RecState.sumRawAfterFirst / 1024.0) / ((last - first) / 1e9) * 100.0;
                s.append(String.format(Locale.US, T.t("  取得率 %.1f%%", "  cov %.1f%%"), pct));
            }
            if (RecState.marks > 0) s.append(T.t("  印", "  mk")).append(RecState.marks);
            if (!"記録中".equals(RecState.conn)) s.append('\n').append(T.conn(RecState.conn));
            else if (last > 0 && SystemClock.elapsedRealtimeNanos() - last > 5L * 1000000000L) s.append(T.t("\nデータが届いていません", "\nNo data arriving"));
            else if (!rrArriving()) s.append(T.t("\n⚠ 心拍が取れていません", "\n⚠ No heartbeats"));
        } else {
            s.append(T.t("RRは記録していません", "RR is not being recorded"));
        }
        if (recording) {
            long sec = (SystemClock.elapsedRealtimeNanos() - recStartMono) / 1000000000L;
            s.append(String.format(Locale.US, T.t("\n● 録画 %02d:%02d", "\n● REC %02d:%02d"), sec / 60, sec % 60));
            double mb = -1;
            try {
                if (pfd != null) mb = pfd.getStatSize() / 1e6;      // 実際のファイルの大きさ
            } catch (Exception ignored) { }
            if (mb < 0) mb = sec * mbPerMin(quality) / 60.0;
            s.append(String.format(Locale.US, "  %.0f MB", mb));
            if (mb >= 27 && mb < 40) s.append(T.t(" ⚠30MB間近", " ⚠near 30MB"));
            if (!audioOk) s.append(T.t("（音声なし）", " (no audio)"));
        } else {
            s.append(String.format(Locale.US, T.t("\n画質 %s %dx%d 約%.0fMB/分", "\nQuality %s %dx%d ~%.0fMB/min"), qName(quality),
                    videoSize.getWidth(), videoSize.getHeight(), mbPerMin(quality)));
        }
        info.setText(s.toString());
        for (int i = 0; i < 3; i++) {
            qBtn[i].setText((i == quality ? "✓ " : "") + qName(i));
            qBtn[i].setTypeface(i == quality ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            qBtn[i].setEnabled(!recording && !busy);
        }
        recBtn.setText(busy ? T.t("準備中…", "Preparing…") : recording ? (protoActive ? T.t("■ 中止", "■ Abort") : T.t("■ 録画停止", "■ Stop")) : T.t("● 録画開始", "● Record"));
        long nowNs = SystemClock.elapsedRealtimeNanos();
        boolean recov = RecState.recording && RecState.autoStopMono > 0;     // 回復期（RRだけ記録中）
        if (recov) sawRecovery = true;
        if (sawRecovery && !RecState.recording) {      // 回復期が終わり、RRも保存された
            sawRecovery = false;
            finish();
            return;
        }
        protoClosing = recov;
        recBtn.setEnabled(!busy && cam != null && !protoPending && !protoClosing);
        protoBtn.setEnabled(!busy && !recording && !protoPending && !protoClosing && cam != null);
        String pt = "";
        if (recov) {
            long rem = Math.max(0, (RecState.autoStopMono - nowNs) / 1000000000L + 1);
            pt = String.format(Locale.US, T.t("回復を記録中\n残り %d:%02d\n安静のまま", "Recording recovery\n%d:%02d left\nStay still"), rem / 60, rem % 60);
        } else if (protoActive) {
            double e = (SystemClock.elapsedRealtimeNanos() - protoT0) / 1e9;
            if (e < P_LEAD) {
                pt = T.t("合図まで ", "Signal in ") + (int) Math.ceil(P_LEAD - e);
            } else if (e < P_LEAD + P_WORK) {
                pt = T.t("運動 ", "Exercise ") + (int) (e - P_LEAD) + " / " + P_WORK + T.t(" 秒", " s");
            } else if (e < P_LEAD + P_WORK + 3) {
                pt = P_WORK + T.t("秒経過", " s done");
            } else {
                pt = P_REST + T.t("秒レスト\n残り ", " s rest\nleft: ") + (int) Math.ceil(P_LEAD + P_WORK + P_REST - e) + T.t(" 秒", " s");
            }
        } else if (protoPending || protoArmed) {
            pt = T.t("準備中…", "Preparing…");
        }
        protoView.setText(pt);
        protoView.setVisibility(pt.length() > 0 ? View.VISIBLE : View.GONE);
        if (protoPending && SystemClock.elapsedRealtimeNanos() - protoPendingSince > 6L * 1000000000L) {
            protoPending = false;
            notice = T.t("カメラの準備ができず、研究モードを始められませんでした。", "The camera was not ready, so research mode could not start.");
        }
        recBtn.setTextColor(recording ? Color.rgb(0xD0, 0x10, 0x10) : Color.rgb(0x20, 0x20, 0x20));
        if (recording) {
            boolean on = (SystemClock.elapsedRealtimeNanos() / 500000000L) % 2 == 0;   // 点滅
            recBanner.setText(T.t("● 録画中", "● REC"));
            recBanner.setBackgroundColor(on ? Color.rgb(0xD0, 0x10, 0x10) : Color.rgb(0x80, 0x10, 0x10));
        } else if (busy) {
            recBanner.setText(T.t("準備中…", "Preparing…"));
            recBanner.setBackgroundColor(Color.rgb(0x80, 0x60, 0x00));
        } else {
            recBanner.setText(T.t("録画していません", "Not recording"));
            recBanner.setBackgroundColor(Color.argb(0xCC, 0x40, 0x40, 0x40));
        }
        switchBtn.setEnabled(!recording && !busy);
        markBtn.setEnabled(RecState.recording);
        String m = notice;
        if (m.length() == 0 && !recording && lastSavedVideo.length() > 0) m = T.t("保存しました: ", "Saved: ") + lastSavedVideo;
        msg.setText(m);
        msg.setVisibility(m.length() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ ライフサイクル

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        h.removeCallbacks(tick);
        h.post(tick);
        if (oel.canDetectOrientation()) oel.enable();

        boolean camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if ((!camOk || !audioOk) && !askedPerms) {
            askedPerms = true;
            List<String> ask = new ArrayList<String>();
            if (!camOk) ask.add(Manifest.permission.CAMERA);
            if (!audioOk) ask.add(Manifest.permission.RECORD_AUDIO);
            requestPermissions(ask.toArray(new String[0]), REQ_PERMS);
            return;
        }
        if (!camOk) {
            notice = T.t("カメラの許可が必要です。設定 → アプリ → RRロガー → 権限 で許可してください。", "Camera permission is required. Allow it in Settings → Apps → RR Logger → Permissions.");
            return;
        }
        notice = audioOk ? "" : T.t("マイクの許可がないため、音声なしで録画します（合図音は動画に入りません）。", "No microphone permission: recording without audio (signal beeps will not be in the video).");
        setupCamera();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        h.removeCallbacks(tick);
        oel.disable();
        protoPending = false;
        if (recording) stopRecording(false);
        else if (busy) abortRecording(T.t("画面を離れたため中止", "Cancelled because the screen was left"));
        closeCamera();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceAlive = true;
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceAlive = true;
        surfW = width;
        surfH = height;
        tryOpen();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceAlive = false;
        surfW = 0;
        surfH = 0;
        if (recording) stopRecording(false);
        closeCamera();
    }

    // ------------------------------------------------------------------ カメラの準備

    private static boolean sameAspect(Size a, Size b) {
        return a.getWidth() * b.getHeight() == b.getWidth() * a.getHeight();
    }

    /** 使うカメラ・録画サイズ・プレビューサイズを決め、表示枠の形を合わせる。 */
    private void setupCamera() {
        try {
            camId = null;
            String fallback = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (fallback == null) fallback = id;
                int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
                if (facing == want) { camId = id; break; }
            }
            if (camId == null) camId = fallback;
            if (camId == null) {
                notice = T.t("カメラが見つかりません。", "No camera found.");
                return;
            }
            CameraCharacteristics c = cm.getCameraCharacteristics(camId);
            Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Integer src = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            tsRealtime = src != null && src == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;

            afVideo = false;
            int[] af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (af != null) {
                for (int m : af) if (m == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) afVideo = true;
            }
            fpsRange = null;
            Range<Integer>[] fr = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fr != null) {
                for (Range<Integer> r : fr) {
                    if (r.getLower() == 30 && r.getUpper() == 30) fpsRange = r;
                }
            }

            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] vs = map == null ? null : map.getOutputSizes(MediaRecorder.class);
            Size[] ps = map == null ? null : map.getOutputSizes(SurfaceHolder.class);
            if (vs == null || vs.length == 0 || ps == null || ps.length == 0) {
                notice = T.t("このカメラは録画に使えません。", "This camera cannot be used for recording.");
                camId = null;
                return;
            }
            // 録画: 選んだ画質の大きさがあればそれ。無ければ 16:9 でそれ以下の最大、さらに無ければそれ以下の最大。
            int tw = Q_W[quality], th = Q_H[quality];
            Size best = null;
            for (Size s : vs) {
                if (s.getWidth() == tw && s.getHeight() == th) { best = s; break; }
            }
            if (best == null) {
                for (int pass = 0; pass < 2 && best == null; pass++) {
                    for (Size s : vs) {
                        if (s.getWidth() > tw || s.getHeight() > th) continue;
                        if (pass == 0 && s.getWidth() * 9 != s.getHeight() * 16) continue;
                        if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
                    }
                }
            }
            if (best == null) {          // 目標より小さいものが無い → いちばん小さいもの
                for (Size s : vs) {
                    if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
                }
            }
            videoSize = best;
            // プレビュー: 録画と同じ縦横比で 1280x720 以下の最大（画面表示用なので小さくてよい）
            Size pv = null;
            for (int pass = 0; pass < 2 && pv == null; pass++) {
                for (Size s : ps) {
                    if (s.getWidth() > 1280 || s.getHeight() > 720) continue;
                    if (pass == 0 && !sameAspect(s, videoSize)) continue;
                    if (pv == null || (long) s.getWidth() * s.getHeight() > (long) pv.getWidth() * pv.getHeight()) pv = s;
                }
            }
            if (pv == null) pv = ps[ps.length - 1];
            previewSize = pv;

            root.post(new Runnable() {
                @Override public void run() { applyAspect(); }
            });
        } catch (Exception e) {
            notice = T.t("カメラを準備できません: ", "Cannot prepare the camera: ") + e.getClass().getSimpleName();
        }
    }

    /** 縦持ちの画面に、ゆがまない形でプレビューを置く。 */
    private void applyAspect() {
        int w = root.getWidth(), ht = root.getHeight();
        if (w == 0 || ht == 0) {
            root.postDelayed(new Runnable() {
                @Override public void run() { applyAspect(); }
            }, 50);
            return;
        }
        int vw = w;
        int vh = (int) ((long) w * previewSize.getWidth() / previewSize.getHeight());
        if (vh > ht) {
            vh = ht;
            vw = (int) ((long) ht * previewSize.getHeight() / previewSize.getWidth());
        }
        sv.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        sv.getHolder().setFixedSize(previewSize.getWidth(), previewSize.getHeight());
        tryOpen();
    }

    private boolean surfaceReady() {
        return surfaceAlive && surfW == previewSize.getWidth() && surfH == previewSize.getHeight();
    }

    private void tryOpen() {
        if (!resumed || camId == null || cam != null || opening || !surfaceReady()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            opening = true;
            final String id = camId;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    opening = false;
                    if (!resumed || !surfaceReady() || !id.equals(camId)) {
                        d.close();
                        if (resumed) tryOpen();
                        return;
                    }
                    cam = d;
                    startPreview();
                }

                @Override public void onDisconnected(CameraDevice d) {
                    opening = false;
                    if (recording && d == cam) stopRecording(false);
                    d.close();
                    if (d == cam) { cam = null; session = null; }
                }

                @Override public void onError(CameraDevice d, int error) {
                    opening = false;
                    if (recording && d == cam) stopRecording(false);
                    d.close();
                    if (d == cam) { cam = null; session = null; }
                    notice = T.t("カメラのエラー（コード ", "Camera error (code ") + error + T.t("）。他のカメラアプリを閉じて、開き直してください。", "). Close other camera apps and reopen this screen.");
                }
            }, h);
        } catch (Exception e) {
            opening = false;
            notice = T.t("カメラを開けません: ", "Cannot open the camera: ") + e.getClass().getSimpleName();
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (cam != null) cam.close(); } catch (Exception ignored) { }
        cam = null;
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if (afVideo) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
    }

    @SuppressWarnings("deprecation")
    private void startPreview() {
        if (cam == null || !surfaceReady()) return;
        try {
            final CameraDevice d = cam;
            final Surface ps = sv.getHolder().getSurface();
            final CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(ps);
            applyCommon(b);
            d.createCaptureSession(Collections.singletonList(ps), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (d != cam || recording || busy) return;
                    session = s;
                    try {
                        s.setRepeatingRequest(b.build(), null, h);
                    } catch (Exception e) {
                        notice = T.t("プレビューを始められません: ", "Cannot start the preview: ") + e.getClass().getSimpleName();
                    }
                    maybeStartProto();
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    notice = T.t("プレビューを始められません。", "Cannot start the preview.");
                }
            }, h);
        } catch (Exception e) {
            notice = T.t("プレビューを始められません: ", "Cannot start the preview: ") + e.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ 録画

    private int orientationHint() {
        if (front) return (sensorOrientation - deviceDeg + 360) % 360;
        return (sensorOrientation + deviceDeg) % 360;
    }

    @SuppressWarnings("deprecation")
    private void startRecording() {
        if (busy || recording || cam == null || !surfaceReady()) return;
        busy = true;
        notice = "";
        lastSavedVideo = "";
        render();          // すぐ「準備中…」に変える
        try {
            long now = System.currentTimeMillis();
            videoName = "RRVID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now)) + ".mp4";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, videoName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + VIDEO_DIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (videoUri == null) throw new java.io.IOException("insert returned null");
            pfd = getContentResolver().openFileDescriptor(videoUri, "rw");
            if (pfd == null) throw new java.io.IOException("openFileDescriptor returned null");

            audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            if (audioOk) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setOutputFile(pfd.getFileDescriptor());
            rec.setVideoEncodingBitRate(Q_VBPS[quality]);
            rec.setVideoFrameRate(30);
            rec.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (audioOk) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioEncodingBitRate(Q_ABPS[quality]);
                rec.setAudioSamplingRate(48000);
                rec.setAudioChannels(1);
            }
            final int hint = orientationHint();
            rec.setOrientationHint(hint);
            rec.prepare();

            final CameraDevice d = cam;
            final Surface ps = sv.getHolder().getSurface();
            final Surface rs = rec.getSurface();
            final CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(ps);
            b.addTarget(rs);
            applyCommon(b);
            d.createCaptureSession(Arrays.asList(ps, rs), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (d != cam || !busy || rec == null) return;
                    session = s;
                    try {
                        // 先に録画を始めてからコマを流す → 最初に届くコマが動画の0秒になる
                        rec.start();
                        long wall = System.currentTimeMillis();
                        long mono = SystemClock.elapsedRealtimeNanos();
                        recStartMono = mono;
                        awaitingFirst = true;
                        recording = true;
                        busy = false;
                        LoggerService.logFromUi(wall, mono, "VIDEO_START", "file=" + videoName
                                + " size=" + videoSize.getWidth() + "x" + videoSize.getHeight()
                                + " q=" + quality + " vbps=" + Q_VBPS[quality]
                                + " cam=" + (front ? "front" : "back") + " rot=" + hint
                                + " audio=" + (audioOk ? 1 : 0));
                        s.setRepeatingRequest(b.build(), firstFrameCb, h);
                        if (protoArmed) {
                            protoArmed = false;
                            beginProto();
                        }
                    } catch (Exception e) {
                        abortRecording(T.t("録画を始められません: ", "Cannot start recording: ") + e.getClass().getSimpleName());
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    abortRecording(T.t("録画を始められません（カメラの設定に失敗）。", "Cannot start recording (camera configuration failed)."));
                }
            }, h);
        } catch (Exception e) {
            abortRecording(T.t("録画を始められません: ", "Cannot start recording: ") + e.getClass().getSimpleName());
        }
    }

    /** 録画開始後、最初のコマの撮影時刻をCSVに残す（動画の0秒に当たる時刻）。 */
    private final CameraCaptureSession.CaptureCallback firstFrameCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession s, CaptureRequest request, long timestamp, long frameNumber) {
            if (!awaitingFirst) return;
            awaitingFirst = false;
            long nowWall = System.currentTimeMillis();
            long nowMono = SystemClock.elapsedRealtimeNanos();
            long wall = nowWall, mono = nowMono;
            String src = "arrival";
            if (tsRealtime && timestamp > 0 && timestamp <= nowMono && nowMono - timestamp < 5L * 1000000000L) {
                wall = nowWall - (nowMono - timestamp) / 1000000L;
                mono = timestamp;
                src = "sensor";
            }
            LoggerService.logFromUi(wall, mono, "VIDEO_FIRST_FRAME", "file=" + videoName + " src=" + src);
            try {
                if (s == session && recording) s.setRepeatingRequest(request, null, h);   // 以後は毎コマの通知を受けない
            } catch (Exception ignored) { }
        }
    };

    private void releaseRecorder() {
        if (rec != null) {
            try { rec.reset(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
            rec = null;
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) { }
            pfd = null;
        }
    }

    /** 録画開始に失敗したときの後始末。 */
    private void abortRecording(String why) {
        boolean wasRecording = recording;
        busy = false;
        recording = false;
        awaitingFirst = false;
        protoArmed = false;
        cancelProto("start_failed");
        if (wasRecording) {
            LoggerService.logFromUi(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                    "VIDEO_STOP", "file=" + videoName + " failed");
        }
        releaseRecorder();
        if (videoUri != null) {
            try { getContentResolver().delete(videoUri, null, null); } catch (Exception ignored) { }
            videoUri = null;
        }
        notice = why;
        if (resumed && cam != null && surfaceReady()) startPreview();
    }

    private void stopRecording(boolean restartPreview) {
        if (!recording) return;
        recording = false;
        awaitingFirst = false;
        long stopWall = System.currentTimeMillis();
        long stopMono = SystemClock.elapsedRealtimeNanos();
        double durS = (stopMono - recStartMono) / 1e9;
        cancelProto("stopped");
        try {
            if (session != null) {
                session.stopRepeating();
                session.abortCaptures();
            }
        } catch (Exception ignored) { }
        boolean ok = true;
        try {
            rec.stop();
        } catch (Exception e) {
            ok = false;
        }
        LoggerService.logFromUi(stopWall, stopMono, "VIDEO_STOP", "file=" + videoName
                + String.format(Locale.US, " dur=%.1f", durS) + (ok ? "" : " failed"));
        releaseRecorder();
        if (videoUri != null) {
            try {
                if (ok) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(videoUri, done, null, null);
                    lastSavedVideo = T.t("ムービー/", "Movies/") + VIDEO_DIR + "/" + videoName
                            + String.format(Locale.US, T.t("（%.0f秒）", " (%.0f s)"), durS);
                    RecState.lastVideo = lastSavedVideo;
                } else {
                    getContentResolver().delete(videoUri, null, null);
                    notice = T.t("録画を保存できませんでした（短すぎた可能性があります）。", "The video could not be saved (it may have been too short).");
                }
            } catch (Exception e) {
                notice = T.t("録画の保存でエラー: ", "Error while saving the video: ") + e.getClass().getSimpleName();
            }
            videoUri = null;
        }
        if (restartPreview && resumed && cam != null && surfaceReady()) startPreview();
    }

    // ------------------------------------------------------------------ 研究モード

    /** 直近5秒以内に拍（RR）が届いているか。 */
    private static boolean rrArriving() {
        long t = RecState.lastRrMono;
        return t > 0 && SystemClock.elapsedRealtimeNanos() - t < 5L * 1000000000L;
    }

    private long noRrWarnMono = 0;

    private void startProto() {
        if (recording || busy || protoPending || protoClosing) return;
        if (!RecState.recording) {
            notice = T.t("RRを記録していないので、研究モードを始められません。", "RR is not being recorded, so research mode cannot start.");
            return;
        }
        if (!rrArriving()) {
            long now = SystemClock.elapsedRealtimeNanos();
            if (noRrWarnMono == 0 || now - noRrWarnMono > 10L * 1000000000L) {
                noRrWarnMono = now;
                notice = T.t("心拍（RR）が届いていません。機器（", "No heartbeats (RR) are arriving. Check that the device (") + RecState.devName + T.t("）の装着を確認してください。", ") is worn properly. ")
                        + T.t("このまま始める場合は、10秒以内にもう一度押してください。", "To start anyway, press again within 10 seconds.");
                return;
            }
        }
        noRrWarnMono = 0;
        notice = "";
        lastSavedVideo = "";
        protoPending = true;
        protoPendingSince = SystemClock.elapsedRealtimeNanos();
        if (quality != 2) {                 // 極軽に切り替えてから始める
            quality = 2;
            prefs.edit().putInt("quality", 2).apply();
            closeCamera();
            setupCamera();
        } else {
            maybeStartProto();
        }
        render();
    }

    private void maybeStartProto() {
        if (!protoPending || recording || busy || cam == null || session == null || !surfaceReady()) return;
        protoPending = false;
        protoArmed = true;
        startRecording();
        if (!busy && !recording) protoArmed = false;
    }

    private long protoDelayMs(int atSec) {
        long d = atSec * 1000L - (SystemClock.elapsedRealtimeNanos() - protoT0) / 1000000L;
        return d < 0 ? 0 : d;
    }

    private void beginProto() {
        protoActive = true;
        protoT0 = recStartMono;
        LoggerService.logFromUi(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), "PROTO_START",
                "lead=" + P_LEAD + " work=" + P_WORK + " rest=" + P_REST + " recovery=" + P_RECOVERY
                        + " file=" + videoName);
        h.postDelayed(protoGo, protoDelayMs(P_LEAD));
        h.postDelayed(proto60, protoDelayMs(P_LEAD + P_WORK));
        h.postDelayed(protoEnd, protoDelayMs(P_LEAD + P_WORK + P_REST));
    }

    private final Runnable protoGo = new Runnable() {
        @Override public void run() {
            if (protoActive) LoggerService.signal("PROTO_GO", "exercise start", false);
        }
    };

    private final Runnable proto60 = new Runnable() {
        @Override public void run() {
            if (protoActive) LoggerService.signal("PROTO_60S", "exercise end / rest start", true);
        }
    };

    private final Runnable protoEnd = new Runnable() {
        @Override public void run() {
            if (!protoActive) return;
            protoActive = false;
            // 動画はここまで。RRは運動終了から P_RECOVERY 秒たつまで続け、その後は自動で止めて保存する
            LoggerService.logFromUi(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                    "PROTO_END", "video end / RR continues");
            long remainMs = (P_LEAD + P_WORK + P_RECOVERY) * 1000L
                    - (SystemClock.elapsedRealtimeNanos() - protoT0) / 1000000L;
            if (remainMs < 1000) remainMs = 1000;
            LoggerService.scheduleAutoStop(remainMs, "PROTO_RECOVERY_END");
            stopRecording(true);
            render();
        }
    };

    /** 途中でやめたとき。 */
    private void cancelProto(String why) {
        h.removeCallbacks(protoGo);
        h.removeCallbacks(proto60);
        h.removeCallbacks(protoEnd);
        if (protoActive) {
            protoActive = false;
            LoggerService.logFromUi(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(),
                    "PROTO_ABORT", why);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 続きは onResume が行う
    }
}
