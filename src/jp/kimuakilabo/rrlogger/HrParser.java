package jp.kimuakilabo.rrlogger;

/**
 * Bluetooth Heart Rate Measurement (0x2A37) の解読。
 * 1回の通知に入っているRR間隔を「すべて」取り出す。
 */
public final class HrParser {

    public static final class Packet {
        /** 心拍数（bpm）。解読できなければ -1 */
        public int hr = -1;
        /** 接触：1=あり 0=なし -1=機器が非対応 */
        public int contact = -1;
        /** 消費エネルギー（kJ）。無ければ -1 */
        public int energy = -1;
        /** RR間隔（1/1024秒単位の生値）。古い順 */
        public int[] rr = new int[0];
    }

    private HrParser() {}

    public static Packet parse(byte[] v) {
        Packet p = new Packet();
        if (v == null || v.length < 2) return p;
        int flags = v[0] & 0xFF;
        int i;
        if ((flags & 0x01) != 0) {            // 心拍数が16bit
            if (v.length < 3) return p;
            p.hr = (v[1] & 0xFF) | ((v[2] & 0xFF) << 8);
            i = 3;
        } else {                               // 心拍数が8bit
            p.hr = v[1] & 0xFF;
            i = 2;
        }
        int c = (flags >> 1) & 0x03;           // 0,1=非対応 2=対応・接触なし 3=対応・接触あり
        p.contact = (c < 2) ? -1 : (c == 3 ? 1 : 0);
        if ((flags & 0x08) != 0) {             // 消費エネルギーあり
            if (i + 1 < v.length) p.energy = (v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8);
            i += 2;
        }
        if ((flags & 0x10) != 0 && i < v.length) {   // RR間隔あり（複数可）
            int n = (v.length - i) / 2;
            p.rr = new int[n];
            for (int k = 0; k < n; k++) {
                p.rr[k] = (v[i + 2 * k] & 0xFF) | ((v[i + 2 * k + 1] & 0xFF) << 8);
            }
        }
        return p;
    }
}
