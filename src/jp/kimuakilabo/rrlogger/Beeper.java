package jp.kimuakilabo.rrlogger;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.SystemClock;

/**
 * 合図音。あらかじめ音を用意しておき、指示から鳴るまでの遅れを小さくする。
 * さらに、音が実際にスピーカーへ出た時刻を端末の音声時計から求めて知らせる（反応時間の基準用）。
 * 音は 1000 Hz・0.1 秒（2連のときは 0.15 秒あけてもう1回）。
 */
final class Beeper {

    interface Listener {
        /** startMonoNs: 音の先頭が出た時刻（elapsedRealtimeNanos）。分からなければ -1。 */
        void onSound(long startMonoNs);
    }

    private static final int SR = 48000;
    private final Handler h;
    private AudioTrack spare1, spare2;      // 次に鳴らす分（1回音・2連音）を先に作っておく

    Beeper(Handler h) {
        this.h = h;
        spare1 = make(1);
        spare2 = make(2);
    }

    private static short[] samples(int beeps) {
        int beepN = SR / 10;              // 0.10 秒
        int gapN = SR * 15 / 100;         // 0.15 秒
        int tailN = SR * 45 / 100;        // 鳴り終わった後の無音（時刻を読むあいだ再生を続けるため）
        int fadeIn = SR / 1000;           // 1 ms（立ち上がりを鋭く）
        int fadeOut = SR * 5 / 1000;      // 5 ms
        short[] d = new short[beeps * beepN + (beeps - 1) * gapN + tailN];
        for (int b = 0; b < beeps; b++) {
            int off = b * (beepN + gapN);
            for (int i = 0; i < beepN; i++) {
                double a = 0.9;
                if (i < fadeIn) a *= (double) i / fadeIn;
                if (i > beepN - fadeOut) a *= (double) (beepN - i) / fadeOut;
                d[off + i] = (short) (Math.sin(2 * Math.PI * 1000.0 * i / SR) * a * 32767);
            }
        }
        return d;
    }

    private static AudioTrack make(int beeps) {
        try {
            short[] d = samples(beeps);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(d.length * 2)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            t.write(d, 0, d.length);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** 鳴らす。鳴らせなければ false（呼び出し側が別の方法で鳴らす）。 */
    boolean play(final boolean doubleBeep, final Listener l) {
        final AudioTrack t = doubleBeep ? spare2 : spare1;
        if (t == null) {
            if (doubleBeep) spare2 = make(2); else spare1 = make(1);
            return false;
        }
        final long cmd;
        try {
            cmd = SystemClock.elapsedRealtimeNanos();
            t.play();
        } catch (Exception e) {
            return false;
        }
        if (doubleBeep) spare2 = null; else spare1 = null;
        // 次の分を作り直し、使い終わった分は後で片づける
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (doubleBeep) { if (spare2 == null) spare2 = make(2); }
                else { if (spare1 == null) spare1 = make(1); }
            }
        }, 150);
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try { t.release(); } catch (Exception ignored) { }
            }
        }, 1500);
        if (l != null) poll(t, cmd, l, 0);
        return true;
    }

    /** 音声時計から「先頭の音が出た時刻」を読む。0.05 秒ぶん以上進んでから読む（出だしの値は不安定なため）。 */
    private void poll(final AudioTrack t, final long cmd, final Listener l, final int tries) {
        h.postDelayed(new Runnable() {
            @Override public void run() {
                long start = -1;
                try {
                    AudioTimestamp ts = new AudioTimestamp();
                    if (t.getTimestamp(ts) && ts.framePosition >= SR / 20) {
                        long off = SystemClock.elapsedRealtimeNanos() - System.nanoTime();
                        long s = ts.nanoTime - ts.framePosition * 1000000000L / SR + off;
                        if (s > cmd - 5000000L && s < cmd + 1000000000L) start = s;
                    }
                } catch (Exception ignored) { }
                if (start >= 0) l.onSound(start);
                else if (tries < 40) poll(t, cmd, l, tries + 1);
                else l.onSound(-1);
            }
        }, 10);
    }

    void release() {
        try { if (spare1 != null) spare1.release(); } catch (Exception ignored) { }
        try { if (spare2 != null) spare2.release(); } catch (Exception ignored) { }
        spare1 = null;
        spare2 = null;
    }
}
