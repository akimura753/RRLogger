package jp.kimuakilabo.rrlogger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 記録中はアプリ専用領域に書き、停止時に「ダウンロード/RRLogger」へ写す。 */
final class Exporter {
    private Exporter() {}

    static final String SUBDIR = "RRLogger";
    static final String PREFS = "rrlogger";

    static File recDir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "rec");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** ダウンロード/RRLogger へ写す。成功したら元ファイルを消してUriを返す。失敗時は null（元ファイルは残る）。 */
    static synchronized Uri export(Context c, File f) {
        if (f == null || !f.exists() || f.length() == 0) return null;
        ContentResolver r = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(f); OutputStream out = r.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("openOutputStream returned null");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putString("lastSavedUri", uri.toString()).putString("lastSavedName", f.getName()).apply();
            return uri;
        } catch (Exception e) {
            if (uri != null) {
                try { r.delete(uri, null, null); } catch (Exception ignored) { }
            }
            return null;
        }
    }

    /** 異常終了などで残ったファイルを写す。戻り値は写せた本数。 */
    static synchronized int exportLeftovers(Context c, String exceptName) {
        File[] fs = recDir(c).listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (!f.getName().endsWith(".csv")) continue;
            if (exceptName != null && exceptName.equals(f.getName())) continue;
            if (f.getName().equals(RecState.activeFile)) continue;   // いま記録中のファイル
            if (export(c, f) != null) n++;
        }
        return n;
    }
}
