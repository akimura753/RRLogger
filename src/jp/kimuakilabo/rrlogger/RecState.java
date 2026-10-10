package jp.kimuakilabo.rrlogger;

/** 記録サービスの現在の状態。画面はこれを定期的に読んで表示する。 */
final class RecState {
    private RecState() {}

    static volatile boolean recording = false;
    static volatile String conn = "停止中";
    static volatile String devName = "";
    static volatile String devAddr = "";

    static volatile int hr = -1;
    static volatile int contact = -1;
    static volatile int lastRrRaw = -1;
    static volatile int battery = -1;

    static volatile long notifs = 0;
    static volatile long beats = 0;
    static volatile long startWall = 0;
    static volatile long startMono = 0;       // elapsedRealtimeNanos
    static volatile long firstNotifMono = 0;  // 最初の通知を受けた時刻
    static volatile long lastNotifMono = 0;
    static volatile long autoStopMono = 0;    // この時刻になったら自動で記録を止める（0=予定なし）
    static volatile long lastRrMono = 0;      // 最後にRR（拍）が入っていた通知の時刻
    static volatile long sumRawAfterFirst = 0; // 最初の通知より後に届いたRRの合計（1/1024秒）

    static volatile int marks = 0;
    static volatile int reconnects = 0;

    static volatile String fileName = "";
    static volatile String activeFile = "";   // 書き込み中のファイル名（無ければ空）
    static volatile String lastSaved = "";
    static volatile String lastSavedUri = "";
    static volatile String lastVideo = "";
    static volatile String error = "";

    static void resetForStart() {
        hr = -1; contact = -1; lastRrRaw = -1; battery = -1;
        notifs = 0; beats = 0; firstNotifMono = 0; lastNotifMono = 0; lastRrMono = 0; autoStopMono = 0; sumRawAfterFirst = 0;
        marks = 0; reconnects = 0; error = "";
    }
}
