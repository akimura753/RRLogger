RR Logger
An Android app that logs every RR interval (beat-to-beat interval) sent by a Bluetooth LE heart-rate chest strap, with the phone's clock time, while the same phone records video.
It was written for movement research in which heart rate and video have to share one clock: the app keeps logging in the background, can record video itself with the heart rate overlaid on the preview, and has a timed "research mode" protocol.
日本語の説明は下にあります。
What it does
Connects to any sensor that implements the standard Bluetooth Heart Rate Service (0x180D) and reads all RR intervals in each Heart Rate Measurement notification (some loggers keep only the first one).
Writes one CSV row per beat, plus event rows (connect, disconnect, markers, video start/stop).
Runs as a foreground service: recording continues with the screen off or while another app (for example the stock camera) is in front. Reconnects automatically after a dropout.
In-app camera with heart rate, last RR and coverage shown over the preview. Three video qualities: Standard 1920x1080 (~91 MB/min), Light 1280x720 (~29 MB/min), Ultra-light 640x360 (~12 MB/min).
Logs the capture time of the first video frame (`VIDEO_FIRST_FRAME`), so video time 0 can be placed on the RR timeline.
Marker button with a beep; the time the beep actually left the speaker is logged (`*_SOUND` rows).
Research mode: signal 5 s after recording starts, 60 s exercise, video ends 30 s later, RR continues until 3 min after the exercise, then everything is saved automatically.
English / Japanese interface (button at the bottom of the first screen).
Requirements
Android 10 or later (minSdk 29, targetSdk 34).
A heart-rate sensor that sends RR intervals. Optical armbands usually do not.
Tested with
Motorola moto g (2025), Android 16, with a COOSPO H9Z chest strap.
Nothing else has been tested. On that combination, recordings of 1.5 to 5 minutes contained all beats (sum of RR / elapsed time = 99.7 to 100.3 %), including while filming.
Output
`Download/RRLogger/RR_yyyyMMdd_HHmmss.csv` and, for in-app video, `Movies/RRLogger/RRVID_yyyyMMdd_HHmmss.mp4`.
Column	Meaning
`epoch_ms`, `time_local`	Time the notification was received (phone clock). Not the time of the beat.
`elapsed_s`	Seconds since recording started (monotonic clock).
`event`	Empty for beat rows; otherwise the event name.
`hr_bpm`, `contact`	Heart rate and skin-contact flag reported by the sensor.
`rr_count`, `rr_index`	Number of RR intervals in the notification, and this row's position among them.
`rr_ms`, `rr_raw_1024`	RR interval in ms, and the raw value in 1/1024 s.
`rr_cum_s`	Running sum of RR. Use this to reconstruct beat times.
`note`	Details for event rows.
Events: `START`, `CONNECT`, `NOTIFY_ON`, `BATTERY`, `MARK`, `DISCONNECT`, `ERROR`, `STOP`, `VIDEO_START`, `VIDEO_FIRST_FRAME`, `VIDEO_STOP`, `PROTO_START`, `PROTO_GO`, `PROTO_60S`, `PROTO_END`, `PROTO_RECOVERY_END`, `PROTO_ABORT`, and `<event>_SOUND`.
Rows are written in the order things are logged, so a `_SOUND` or `VIDEO_FIRST_FRAME` row can carry a time slightly earlier than the row above it.
Known limits
Notifications arrive about once per second with one or two beats each, so the receive time is not the beat time.
The beep is heard roughly 0.2 to 0.3 s after the command on the tested phone. Use the `_SOUND` row, or the beep in the video's audio track, as the stimulus time. In one recording those two differed by a constant 75 ms; which of them is closer to the true time has not been established.
Video is 30 frames per second, so timing read from frames has a resolution of about 33 ms.
The H9Z's RR values vary very little from beat to beat at rest. Whether the strap smooths them has not been checked against an ECG.
In-app video stops if the app leaves the foreground. RR logging continues.
Build
The app uses only Android framework APIs (no AndroidX, no Kotlin) and is built without Gradle:
```
./build.sh
```
`build.sh` expects three files in `../tools/`:
`android-34.jar` (the `android.jar` of Android SDK platform 34)
`aapt2` (Android SDK build-tools)
`jadx-all.jar` (used only because it bundles D8 and apksig; any jar containing `com.android.tools.r8.D8` and `com.android.apksig` works)
The first run creates a signing key in `keystore/` (not in this repository). An APK built by someone else is therefore signed with a different key and cannot be installed over one built here; uninstall first.
`test/ReplayTest.java` checks the packet parser and CSV writer on the JVM with synthetic data:
```
javac -encoding UTF-8 -d out/test src/jp/kimuakilabo/rrlogger/HrParser.java src/jp/kimuakilabo/rrlogger/CsvLog.java test/ReplayTest.java
java -cp out/test ReplayTest test/synthetic_replay.tsv
```
Not a medical device
This is a research tool. It is not intended for diagnosis or clinical monitoring.
---
RRロガー
Bluetooth心拍ベルトが送るRR間隔（1拍ごとの間隔）をすべて、スマホの時計の時刻つきで記録するAndroidアプリです。同じスマホで動画を撮りながら使えます。
できること
標準の心拍サービス（0x180D）に対応した機器につながり、通知に入っているRRを全部取り出します。
1拍1行のCSVを書きます。接続・切断・マーカー・録画の開始と終了も行として残ります。
画面を消しても、ほかのアプリ（標準カメラなど）を使っていても記録が続きます。切れたら自動でつなぎ直します。
アプリ内カメラで、心拍・直近のRR・取得率を重ねて表示しながら録画できます。画質は標準・軽い・極軽の3段階です。
動画の最初のコマの時刻を記録するので、動画の0秒をRRの時刻に合わせられます。
マーカーで合図音を鳴らし、音が実際に出た時刻も記録します。
研究モード：録画開始の5秒後に合図、運動60秒、その30秒後に動画終了、RRは運動終了の3分後まで続けて自動保存します。
表示は英語と日本語を切り替えられます（最初の画面のいちばん下のボタン）。
動作を確かめた組み合わせ
moto g（2025）・Android 16 と COOSPO H9Z の組み合わせだけです。
保存先
`ダウンロード/RRLogger/` にCSV、アプリ内で撮った動画は `ムービー/RRLogger/` に入ります。列と出来事名の意味は上の英語の表のとおりです。
知っておくこと
CSVの時刻は「通知を受けた時刻」で、拍そのものの時刻ではありません。拍の時刻は `rr_cum_s` から復元します。
合図音は指示から約0.2〜0.3秒遅れて鳴ります。反応時間の基準には `_SOUND` の行か、動画の音声に入った合図音を使ってください。
動画は30コマ/秒なので、コマから読む時刻の細かさは約33ミリ秒です。
研究用の道具です。診断や医療上の監視には使えません。
