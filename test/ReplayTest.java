import jp.kimuakilabo.rrlogger.CsvLog;
import jp.kimuakilabo.rrlogger.HrParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 実機なしで確かめられる部分（通知の解読とCSVの書式）のテスト。 */
public class ReplayTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }
    static byte[] b(int... v) { byte[] o = new byte[v.length]; for (int i = 0; i < v.length; i++) o[i] = (byte) v[i]; return o; }

    public static void main(String[] a) throws Exception {
        // ---- 解読の単体テスト
        HrParser.Packet p = HrParser.parse(b(0x16, 72, 0x44, 0x03));          // RR 1個 (0x0344=836)
        check(p.hr == 72 && p.contact == 1 && p.rr.length == 1 && p.rr[0] == 836, "8bit HR + RR1個");
        p = HrParser.parse(b(0x16, 150, 0x90, 0x01, 0x95, 0x01, 0x9A, 0x01));  // RR 3個
        check(p.hr == 150 && p.rr.length == 3 && p.rr[0] == 400 && p.rr[1] == 405 && p.rr[2] == 410, "RR3個を古い順に全部取る");
        p = HrParser.parse(b(0x11, 0x2C, 0x01, 0x00, 0x04));                  // 16bit HR=300, RR=1024
        check(p.hr == 300 && p.contact == -1 && p.rr.length == 1 && p.rr[0] == 1024, "16bit HR");
        p = HrParser.parse(b(0x1E, 80, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02));  // energy + RR 2個
        check(p.hr == 80 && p.energy == 16 && p.rr.length == 2 && p.rr[0] == 1024 && p.rr[1] == 512, "消費エネルギーつき");
        p = HrParser.parse(b(0x06, 65));
        check(p.hr == 65 && p.contact == 1 && p.rr.length == 0, "RRなし通知");
        p = HrParser.parse(b(0x04, 0));
        check(p.contact == 0 && p.rr.length == 0, "接触なし");
        p = HrParser.parse(b(0x16, 70, 0x44, 0x03, 0x55));                     // 端数1バイト
        check(p.rr.length == 1 && p.rr[0] == 836, "端数バイトは無視");
        p = HrParser.parse(b(0x16, 70));
        check(p.hr == 70 && p.rr.length == 0, "RRフラグありで中身なし");
        check(HrParser.parse(null).hr == -1 && HrParser.parse(b(0x16)).hr == -1 && HrParser.parse(new byte[0]).rr.length == 0, "空・短すぎる通知で落ちない");
        p = HrParser.parse(b(0x18, 70, 0x10));                                 // energy が切れている
        check(p.rr.length == 0, "途中で切れた通知で落ちない");

        // ---- 合成した通知列（314通知・389拍）を流す
        List<String> lines = Files.readAllLines(Paths.get(a[0]));
        StringWriter sw = new StringWriter();
        CsvLog log = new CsvLog(sw);
        long wall0 = 1791542133000L;
        log.event(wall0, 0, "START", "device=H9Z, test\nx");
        int notifs = 0, expectBeats = 0; double expectMs = 0; long expectRaw = 0;
        for (String ln : lines) {
            String[] f = ln.split("\t");
            double t = Double.parseDouble(f[0]);
            int hr = Integer.parseInt(f[1]);
            int n = f.length - 2;
            byte[] pkt = new byte[2 + 2 * n];
            pkt[0] = 0x16; pkt[1] = (byte) hr;
            for (int k = 0; k < n; k++) {
                double ms = Double.parseDouble(f[2 + k]);
                int raw = (int) Math.round(ms * 1024.0 / 1000.0);
                pkt[2 + 2 * k] = (byte) (raw & 0xFF); pkt[3 + 2 * k] = (byte) (raw >> 8);
                expectMs += ms; expectRaw += raw; expectBeats++;
            }
            int wrote = log.packet(wall0 + Math.round(t * 1000), t, HrParser.parse(pkt));
            if (wrote != n) fails++;
            notifs++;
        }
        log.event(wall0 + 320000, 320.0, "MARK", "1");
        log.close();
        check(notifs == 314, "通知 " + notifs + " 回 (期待 314)");
        check(log.beats() == 389 && expectBeats == 389, "拍数 " + log.beats() + " (期待 389)");
        check(log.cumRaw() == expectRaw, "RR生値の合計が一致");
        double sumS = log.cumRaw() / 1024.0;
        check(Math.abs(sumS - expectMs / 1000.0) < 0.001, String.format("RR合計 %.3f s (expected %.3f s)", sumS, expectMs / 1000.0));

        // ---- 出力CSVを読み戻して確かめる
        String[] out = sw.toString().split("\n");
        int cols = CsvLog.HEADER.split(",").length;
        check(out[0].equals(CsvLog.HEADER), "見出し行");
        boolean colsOk = true; int beatRows = 0, emptyRows = 0, eventRows = 0; double lastCum = 0; long sumRawRead = 0;
        boolean mono = true; double prevT = -1;
        for (int i = 1; i < out.length; i++) {
            String[] f = out[i].split(",", -1);
            if (f.length != cols) { colsOk = false; System.out.println("  bad row " + i + ": " + out[i]); continue; }
            double t = Double.parseDouble(f[2]);
            if (t < prevT) mono = false; prevT = t;
            if (!f[3].isEmpty()) { eventRows++; continue; }
            if (f[9].isEmpty()) { emptyRows++; continue; }
            beatRows++;
            int raw = Integer.parseInt(f[9]); sumRawRead += raw;
            if (Math.abs(Double.parseDouble(f[8]) - raw * 1000.0 / 1024.0) > 0.0006) colsOk = false;
            lastCum = Double.parseDouble(f[10]);
        }
        check(colsOk, "全行が " + cols + " 列で、rr_ms と生値が対応");
        check(mono, "elapsed_s が逆戻りしない");
        check(beatRows == 389 && eventRows == 2, "1拍1行: " + beatRows + " 行、出来事 " + eventRows + " 行、RRなし通知 " + emptyRows + " 行");
        check(sumRawRead == expectRaw && Math.abs(lastCum - sumS) < 0.001, String.format("rr_cum_s の最終値 %.3f s", lastCum));
        System.out.println("--- 出力の先頭 ---");
        for (int i = 0; i < 6; i++) System.out.println(out[i]);
        System.out.println(fails == 0 ? "ALL PASS" : ("FAILED: " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
