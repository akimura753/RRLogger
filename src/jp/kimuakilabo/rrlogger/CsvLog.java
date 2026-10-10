package jp.kimuakilabo.rrlogger;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 記録ファイル（CSV）の書式。1拍＝1行。
 * RRの入らない通知と、開始・停止・接続・切断・マーカーなどの出来事も1行ずつ残す。
 */
public final class CsvLog {

    public static final String HEADER =
            "epoch_ms,time_local,elapsed_s,event,hr_bpm,contact,rr_count,rr_index,rr_ms,rr_raw_1024,rr_cum_s,note";

    private final Writer w;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private long cumRaw = 0;
    private long beats = 0;

    public CsvLog(Writer w) throws IOException {
        this.w = w;
        w.write(HEADER);
        w.write('\n');
    }

    public synchronized long beats() { return beats; }
    public synchronized long cumRaw() { return cumRaw; }

    private String head(long wallMs, double elapsedS) {
        return wallMs + "," + fmt.format(new Date(wallMs)) + ","
                + String.format(Locale.US, "%.3f", elapsedS) + ",";
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** 出来事の行（START / STOP / CONNECT / DISCONNECT / MARK / BATTERY / ERROR） */
    public synchronized void event(long wallMs, double elapsedS, String event, String note) throws IOException {
        w.write(head(wallMs, elapsedS) + clean(event) + ",,,,,,,," + clean(note) + "\n");
        w.flush();
    }

    /** 通知1回ぶんを書く。戻り値は書いたRRの個数 */
    public synchronized int packet(long wallMs, double elapsedS, HrParser.Packet p) throws IOException {
        String h = head(wallMs, elapsedS);
        String hr = p.hr >= 0 ? String.valueOf(p.hr) : "";
        String ct = p.contact >= 0 ? String.valueOf(p.contact) : "";
        int n = p.rr.length;
        if (n == 0) {
            w.write(h + "," + hr + "," + ct + ",0,,,,,\n");
        } else {
            for (int i = 0; i < n; i++) {
                int raw = p.rr[i];
                cumRaw += raw;
                beats++;
                w.write(h + "," + hr + "," + ct + "," + n + "," + (i + 1) + ","
                        + String.format(Locale.US, "%.3f", raw * 1000.0 / 1024.0) + "," + raw + ","
                        + String.format(Locale.US, "%.3f", cumRaw / 1024.0) + ",\n");
            }
        }
        w.flush();
        return n;
    }

    public synchronized void close() throws IOException {
        w.flush();
        w.close();
    }
}
