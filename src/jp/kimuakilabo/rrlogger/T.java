package jp.kimuakilabo.rrlogger;

import android.content.Context;

/** 表示言語の切り替え（英語／日本語）。既定は英語。最初の画面のボタンで切り替え、次回も覚えている。 */
final class T {
    private T() {}

    static volatile boolean en = true;

    static void init(Context c) {
        en = !"ja".equals(c.getSharedPreferences(Exporter.PREFS, Context.MODE_PRIVATE).getString("lang", "en"));
    }

    static void set(Context c, boolean english) {
        c.getSharedPreferences(Exporter.PREFS, Context.MODE_PRIVATE).edit()
                .putString("lang", english ? "en" : "ja").apply();
        en = english;
    }

    static String t(String ja, String english) {
        return en ? english : ja;
    }

    /** 接続状態（内部では日本語の文字列で持っている）を表示用に直す。 */
    static String conn(String s) {
        if (!en || s == null) return s;
        if ("記録中".equals(s)) return "Recording";
        if ("停止中".equals(s)) return "Stopped";
        if ("接続中…".equals(s)) return "Connecting…";
        if ("機器を探しています…".equals(s)) return "Searching for device…";
        if ("接続（準備中）".equals(s)) return "Connected (preparing)";
        if ("切断 → 再接続待ち".equals(s)) return "Disconnected → reconnecting";
        if ("Bluetoothがオフです".equals(s)) return "Bluetooth is off";
        if ("Bluetoothの権限がありません".equals(s)) return "No Bluetooth permission";
        if ("接続エラー".equals(s)) return "Connection error";
        if ("接続を開始できません".equals(s)) return "Cannot start connection";
        if ("この機器には心拍サービスがありません".equals(s)) return "This device has no heart rate service";
        return s;
    }
}
