package cz.jirka.prehravac.recovery;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Obnova MP4/MOV videa, kteremu chybi index (moov atom).
 * Cista Java bez Android zavislosti, aby se dala testovat na PC.
 *
 * Postup:
 *  1. najde v rozbitem souboru data (mdat),
 *  2. ziska nastaveni kodeku (SPS/PPS) - primo z dat, nebo z referencniho videa,
 *  3. projde data a rozpozna jednotlive snimky H.264 / HEVC (zvuk mezi nimi preskoci),
 *  4. dopocita poradi zobrazeni (B-snimky) a klicove snimky,
 *  5. zapise nove, platne MP4.
 */
public final class MoovRecovery {

    static final boolean DEBUG = Boolean.getBoolean("mr.debug");

    public interface Listener {
        void progress(int percent);
    }

    public static final class RecoveryException extends Exception {
        public RecoveryException(String msg) {
            super(msg);
        }
    }

    public static final class Result {
        public String codec;
        public int width, height;
        public int frames, keyframes;
        public double seconds, fps;
        public long skippedBytes;
        public String configSource;
        public boolean bFrames;

        @Override
        public String toString() {
            return codec + " " + width + "x" + height + ", " + frames + " snimku (" + keyframes + " klicovych), "
                    + String.format("%.1f", seconds) + " s @ " + String.format("%.2f", fps) + " fps, nastaveni: "
                    + configSource + (bFrames ? ", B-snimky" : "") + ", preskoceno " + skippedBytes + " B";
        }
    }

    // =====================================================================
    // Cteni souboru
    // =====================================================================

    static final class Src {
        final FileChannel ch;
        final long size;
        private final ByteBuffer buf = ByteBuffer.allocate(1 << 22);
        private long bufStart = -1;
        private int bufLen = 0;

        Src(FileChannel ch) throws IOException {
            this.ch = ch;
            this.size = ch.size();
        }

        private void fill(long p) throws IOException {
            buf.clear();
            bufStart = p;
            int total = 0;
            while (buf.hasRemaining()) {
                int r = ch.read(buf, p + total);
                if (r <= 0) break;
                total += r;
            }
            bufLen = total;
            if (bufLen <= 0) throw new IOException("EOF at " + p);
        }

        int u8(long p) throws IOException {
            if (p < bufStart || p >= bufStart + bufLen) fill(p);
            return buf.get((int) (p - bufStart)) & 0xFF;
        }

        int u16(long p) throws IOException {
            return (u8(p) << 8) | u8(p + 1);
        }

        long u32(long p) throws IOException {
            return ((long) u8(p) << 24) | ((long) u8(p + 1) << 16) | ((long) u8(p + 2) << 8) | u8(p + 3);
        }

        long u64(long p) throws IOException {
            return (u32(p) << 32) | u32(p + 4);
        }

        byte[] bytes(long p, int n) throws IOException {
            n = (int) Math.max(0, Math.min(n, size - p));
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) out[i] = (byte) u8(p + i);
            return out;
        }

        String type(long p) throws IOException {
            char[] c = new char[4];
            for (int i = 0; i < 4; i++) c[i] = (char) u8(p + i);
            return new String(c);
        }
    }

    // =====================================================================
    // Bitovy ctenar (exp-Golomb) s odstranenim emulation prevention bajtu
    // =====================================================================

    static final class Bits {
        final byte[] d;
        int pos;

        Bits(byte[] nal, int skipBytes) {
            d = unescape(nal, skipBytes);
        }

        static byte[] unescape(byte[] b, int from) {
            byte[] out = new byte[Math.max(0, b.length - from)];
            int n = 0, zeros = 0;
            for (int i = from; i < b.length; i++) {
                int v = b[i] & 0xFF;
                if (zeros >= 2 && v == 3) {
                    zeros = 0;
                    continue;
                }
                zeros = (v == 0) ? zeros + 1 : 0;
                out[n++] = (byte) v;
            }
            return Arrays.copyOf(out, n);
        }

        int bit() {
            if (pos >= d.length * 8) throw new IndexOutOfBoundsException("bits");
            int v = (d[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return v;
        }

        long u(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | bit();
            return v;
        }

        int ui(int n) {
            return (int) u(n);
        }

        int ue() {
            int z = 0;
            while (bit() == 0) {
                if (++z > 31) throw new IndexOutOfBoundsException("ue");
            }
            return (int) ((1L << z) - 1 + (z > 0 ? u(z) : 0));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    // =====================================================================
    // Parametry kodeku
    // =====================================================================

    static final class Params {
        boolean hevc;
        byte[] vps, sps, pps;
        int lengthSize = 4;
        byte[] sampleEntry; // hotovy stsd zaznam (z reference), jinak null
        // z SPS
        int width, height;
        int log2MaxFrameNum, pocType, log2MaxPocLsb;
        boolean frameMbsOnly = true, sepColour;
        int chromaFormat = 1, bitDepthLuma = 8, bitDepthChroma = 8;
        long vuiUnits, vuiScale;
        int maxSubLayers;
        int ctbAddrBits = -1, picSizeInCtbs = 0; // HEVC
        // z PPS
        int ppsId = 0;
        // AVC PPS (pro hlubsi kontrolu hlavicky rezu)
        boolean avcDeep = false, entropyCabac, bottomFieldPicOrder, weightedPred, redundantPicCnt;
        int weightedBipred, picInitQp = 26, numRefL0 = 1, numRefL1 = 1;
        boolean deltaPicOrderAlwaysZero;
        boolean outputFlagPresent, dependentSliceEnabled;
        int numExtraSliceBits;
        String source;
        // casovani z reference
        long refTimescale, refDelta;
    }

    private static final int[] HIGH_PROFILES = {100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135};
    private static final int[] AVC_PROFILES = {66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135};

    private static boolean in(int v, int[] a) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static void skipScalingList(Bits b, int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            last = (next == 0) ? last : next;
        }
    }

    static void parseAvcSps(Params p) {
        Bits b = new Bits(p.sps, 1);
        int profile = b.ui(8);
        b.u(8);
        b.u(8);
        b.ue();
        if (in(profile, HIGH_PROFILES)) {
            p.chromaFormat = b.ue();
            if (p.chromaFormat == 3) p.sepColour = b.bit() == 1;
            p.bitDepthLuma = b.ue() + 8;
            p.bitDepthChroma = b.ue() + 8;
            b.bit();
            if (b.bit() == 1) {
                int cnt = p.chromaFormat != 3 ? 8 : 12;
                for (int i = 0; i < cnt; i++) if (b.bit() == 1) skipScalingList(b, i < 6 ? 16 : 64);
            }
        }
        p.log2MaxFrameNum = b.ue() + 4;
        p.pocType = b.ue();
        if (p.pocType == 0) {
            p.log2MaxPocLsb = b.ue() + 4;
        } else if (p.pocType == 1) {
            p.deltaPicOrderAlwaysZero = b.bit() == 1;
            b.se();
            b.se();
            int n = b.ue();
            for (int i = 0; i < n; i++) b.se();
        }
        b.ue();
        b.bit();
        int wMbs = b.ue() + 1;
        int hMap = b.ue() + 1;
        p.frameMbsOnly = b.bit() == 1;
        if (!p.frameMbsOnly) b.bit();
        b.bit();
        int cl = 0, cr = 0, ct = 0, cb = 0;
        if (b.bit() == 1) {
            cl = b.ue();
            cr = b.ue();
            ct = b.ue();
            cb = b.ue();
        }
        int fmo = p.frameMbsOnly ? 1 : 0;
        int cropX = (p.chromaFormat == 0 || p.sepColour || p.chromaFormat == 3) ? 1 : 2;
        int cropY = (p.chromaFormat == 1 && !p.sepColour ? 2 : 1) * (2 - fmo);
        p.width = wMbs * 16 - (cl + cr) * cropX;
        p.height = (2 - fmo) * hMap * 16 - (ct + cb) * cropY;
        try {
            if (b.bit() == 1) { // VUI
                if (b.bit() == 1) {
                    if (b.ui(8) == 255) {
                        b.u(16);
                        b.u(16);
                    }
                }
                if (b.bit() == 1) b.bit();
                if (b.bit() == 1) {
                    b.u(3);
                    b.bit();
                    if (b.bit() == 1) b.u(24);
                }
                if (b.bit() == 1) {
                    b.ue();
                    b.ue();
                }
                if (b.bit() == 1) {
                    p.vuiUnits = b.u(32);
                    p.vuiScale = b.u(32);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void parseAvcPps(Params p) {
        Bits b = new Bits(p.pps, 1);
        p.ppsId = b.ue();
        try {
            b.ue();
            p.entropyCabac = b.bit() == 1;
            p.bottomFieldPicOrder = b.bit() == 1;
            int sliceGroups = b.ue();
            if (sliceGroups != 0) return; // slice groups - hlubokou kontrolu vynechame
            p.numRefL0 = b.ue() + 1;
            p.numRefL1 = b.ue() + 1;
            p.weightedPred = b.bit() == 1;
            p.weightedBipred = b.ui(2);
            p.picInitQp = 26 + b.se();
            b.se();
            b.se();
            b.bit();
            b.bit();
            p.redundantPicCnt = b.bit() == 1;
            p.avcDeep = true;
        } catch (RuntimeException e) {
            p.avcDeep = false;
        }
    }

    private static void skipPtl(Bits b, int maxSub) {
        b.u(8);
        b.u(32);
        b.u(32);
        b.u(16);
        b.u(8);
        boolean[] prof = new boolean[maxSub], lvl = new boolean[maxSub];
        for (int i = 0; i < maxSub; i++) {
            prof[i] = b.bit() == 1;
            lvl[i] = b.bit() == 1;
        }
        if (maxSub > 0) for (int i = maxSub; i < 8; i++) b.u(2);
        for (int i = 0; i < maxSub; i++) {
            if (prof[i]) {
                b.u(8);
                b.u(32);
                b.u(32);
                b.u(16);
            }
            if (lvl[i]) b.u(8);
        }
    }

    static void parseHevcSps(Params p) {
        Bits b = new Bits(p.sps, 2);
        b.u(4);
        p.maxSubLayers = b.ui(3);
        b.bit();
        skipPtl(b, p.maxSubLayers);
        b.ue();
        p.chromaFormat = b.ue();
        if (p.chromaFormat == 3) p.sepColour = b.bit() == 1;
        int w = b.ue(), h = b.ue();
        int rawW = w, rawH = h;
        if (b.bit() == 1) {
            int cl = b.ue(), cr = b.ue(), ct = b.ue(), cb = b.ue();
            int subW = (p.chromaFormat == 1 || p.chromaFormat == 2) ? 2 : 1;
            int subH = p.chromaFormat == 1 ? 2 : 1;
            w -= subW * (cl + cr);
            h -= subH * (ct + cb);
        }
        p.width = w;
        p.height = h;
        p.bitDepthLuma = b.ue() + 8;
        p.bitDepthChroma = b.ue() + 8;
        p.log2MaxPocLsb = b.ue() + 4;
        try {
            boolean ordering = b.bit() == 1;
            for (int i = ordering ? 0 : p.maxSubLayers; i <= p.maxSubLayers; i++) {
                b.ue();
                b.ue();
                b.ue();
            }
            int log2MinCb = b.ue() + 3;
            int ctbLog2 = log2MinCb + b.ue();
            int ctb = 1 << ctbLog2;
            p.picSizeInCtbs = ((rawW + ctb - 1) / ctb) * ((rawH + ctb - 1) / ctb);
            p.ctbAddrBits = 32 - Integer.numberOfLeadingZeros(Math.max(1, p.picSizeInCtbs - 1));
        } catch (RuntimeException e) {
            p.ctbAddrBits = -1;
        }
    }

    static void parseHevcPps(Params p) {
        Bits b = new Bits(p.pps, 2);
        p.ppsId = b.ue();
        b.ue();
        p.dependentSliceEnabled = b.bit() == 1;
        p.outputFlagPresent = b.bit() == 1;
        p.numExtraSliceBits = b.ui(3);
    }

    // =====================================================================
    // Nastaveni kodeku primo z dat (parametry ulozene v obrazovych datech)
    // =====================================================================

    static Params findInBandParams(Src s, long start, long end, Listener l) throws IOException {
        long limit = Math.min(end, start + (48L << 20));
        long step = Math.max(1, (limit - start) / 20);
        long nextReport = start + step;
        for (long p = start; p + 16 < limit; p++) {
            if (p >= nextReport) {
                if (l != null) l.progress((int) (10 * (p - start) / Math.max(1, limit - start)));
                nextReport += step;
            }
            if (s.u8(p) != 0 || s.u8(p + 1) != 0) continue;
            int len = s.u16(p + 2);
            if (len < 4 || len > 2048) continue;
            int h = s.u8(p + 4);
            // H.264 SPS
            if ((h & 0x9F) == 0x07 && (h & 0x60) != 0 && in(s.u8(p + 5), AVC_PROFILES)) {
                long q = p + 4 + len;
                if (q + 5 > end) continue;
                long len2 = s.u32(q);
                int h2 = s.u8(q + 4);
                if (len2 >= 1 && len2 <= 2048 && (h2 & 0x9F) == 0x08 && (h2 & 0x60) != 0) {
                    Params prm = new Params();
                    prm.hevc = false;
                    prm.sps = s.bytes(p + 4, len);
                    prm.pps = s.bytes(q + 4, (int) len2);
                    try {
                        parseAvcSps(prm);
                        parseAvcPps(prm);
                        if (prm.width < 16 || prm.height < 16 || prm.width > 8192 || prm.height > 8192) continue;
                    } catch (RuntimeException e) {
                        continue;
                    }
                    prm.source = "z dat videa";
                    return prm;
                }
            }
            // HEVC VPS -> SPS -> PPS
            if (h == 0x40 && s.u8(p + 5) == 0x01 && len <= 512) {
                long q = p + 4 + len;
                if (q + 6 > end) continue;
                long lenS = s.u32(q);
                if (lenS < 4 || lenS > 2048 || s.u8(q + 4) != 0x42 || s.u8(q + 5) != 0x01) continue;
                long r = q + 4 + lenS;
                if (r + 6 > end) continue;
                long lenP = s.u32(r);
                if (lenP < 2 || lenP > 2048 || s.u8(r + 4) != 0x44 || s.u8(r + 5) != 0x01) continue;
                Params prm = new Params();
                prm.hevc = true;
                prm.vps = s.bytes(p + 4, len);
                prm.sps = s.bytes(q + 4, (int) lenS);
                prm.pps = s.bytes(r + 4, (int) lenP);
                try {
                    parseHevcSps(prm);
                    parseHevcPps(prm);
                    if (prm.width < 16 || prm.height < 16 || prm.width > 8192 || prm.height > 8192) continue;
                } catch (RuntimeException e) {
                    continue;
                }
                prm.source = "z dat videa";
                return prm;
            }
        }
        return null;
    }

    // =====================================================================
    // Nastaveni kodeku z referencniho videa
    // =====================================================================

    static final class Box {
        final String type;
        final int start, dataStart, end; // v ramci byte[]

        Box(String type, int start, int dataStart, int end) {
            this.type = type;
            this.start = start;
            this.dataStart = dataStart;
            this.end = end;
        }
    }

    private static long rd32(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static int rd16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    static List<Box> children(byte[] b, int from, int to) {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = rd32(b, p);
            String t = new String(b, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) {
                size = (rd32(b, p + 8) << 32) | rd32(b, p + 12);
                hdr = 16;
            } else if (size == 0) size = to - p;
            if (size < hdr || p + size > to) break;
            out.add(new Box(t, p, p + hdr, (int) (p + size)));
            p += size;
        }
        return out;
    }

    static Box child(byte[] b, Box parent, String type) {
        for (Box c : children(b, parent.dataStart, parent.end)) if (c.type.equals(type)) return c;
        return null;
    }

    static Params paramsFromReference(Src ref) throws IOException, RecoveryException {
        // najdi moov na nejvyssi urovni
        long p = 0;
        byte[] moov = null;
        while (p + 8 <= ref.size) {
            long size = ref.u32(p);
            String t = ref.type(p + 4);
            int hdr = 8;
            if (size == 1) {
                size = ref.u64(p + 8);
                hdr = 16;
            } else if (size == 0) size = ref.size - p;
            if (size < 8) break;
            if (t.equals("moov")) {
                if (size > (128L << 20)) throw new RecoveryException("Referencni video ma nesmyslne velky index.");
                moov = ref.bytes(p, (int) size);
                break;
            }
            p += size;
        }
        if (moov == null)
            throw new RecoveryException("Referencni video samo nema index (moov). Vyber jine, ktere jde normalne prehrat.");

        Box root = new Box("moov", 0, 8, moov.length);
        for (Box trak : children(moov, root.dataStart, root.end)) {
            if (!trak.type.equals("trak")) continue;
            Box mdia = child(moov, trak, "mdia");
            if (mdia == null) continue;
            Box hdlr = child(moov, mdia, "hdlr");
            if (hdlr == null) continue;
            String handler = new String(moov, hdlr.dataStart + 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!handler.equals("vide")) continue;

            Params prm = new Params();
            Box mdhd = child(moov, mdia, "mdhd");
            if (mdhd != null) {
                int v = moov[mdhd.dataStart] & 0xFF;
                prm.refTimescale = rd32(moov, mdhd.dataStart + (v == 1 ? 20 : 12));
            }
            Box minf = child(moov, mdia, "minf");
            Box stbl = minf == null ? null : child(moov, minf, "stbl");
            if (stbl == null) continue;
            Box stts = child(moov, stbl, "stts");
            if (stts != null && rd32(moov, stts.dataStart + 4) > 0) {
                // nejcastejsi delka snimku
                long n = rd32(moov, stts.dataStart + 4);
                long bestCount = -1;
                for (int i = 0; i < n && stts.dataStart + 16 + i * 8 <= stts.end; i++) {
                    long cnt = rd32(moov, stts.dataStart + 8 + i * 8);
                    long delta = rd32(moov, stts.dataStart + 12 + i * 8);
                    if (cnt > bestCount && delta > 0) {
                        bestCount = cnt;
                        prm.refDelta = delta;
                    }
                }
            }
            Box stsd = child(moov, stbl, "stsd");
            if (stsd == null) continue;
            List<Box> entries = children(moov, stsd.dataStart + 8, stsd.end);
            if (entries.isEmpty()) continue;
            Box e = entries.get(0);
            String et = e.type;
            boolean avc = et.equals("avc1") || et.equals("avc3");
            boolean hevc = et.equals("hvc1") || et.equals("hev1");
            if (!avc && !hevc)
                throw new RecoveryException("Referencni video pouziva kodek " + et + ". Umim zatim H.264 a H.265 (HEVC).");
            prm.hevc = hevc;
            prm.sampleEntry = Arrays.copyOfRange(moov, e.start, e.end);
            int cfgFrom = e.dataStart + 78;
            for (Box c : children(moov, cfgFrom, e.end)) {
                if (avc && c.type.equals("avcC")) {
                    int q = c.dataStart;
                    prm.lengthSize = (moov[q + 4] & 3) + 1;
                    int nSps = moov[q + 5] & 0x1F;
                    q += 6;
                    for (int i = 0; i < nSps; i++) {
                        int len = rd16(moov, q);
                        if (prm.sps == null) prm.sps = Arrays.copyOfRange(moov, q + 2, q + 2 + len);
                        q += 2 + len;
                    }
                    int nPps = moov[q] & 0xFF;
                    q++;
                    for (int i = 0; i < nPps; i++) {
                        int len = rd16(moov, q);
                        if (prm.pps == null) prm.pps = Arrays.copyOfRange(moov, q + 2, q + 2 + len);
                        q += 2 + len;
                    }
                } else if (hevc && c.type.equals("hvcC")) {
                    int q = c.dataStart;
                    prm.lengthSize = (moov[q + 21] & 3) + 1;
                    int arrays = moov[q + 22] & 0xFF;
                    q += 23;
                    for (int a = 0; a < arrays; a++) {
                        int type = moov[q] & 0x3F;
                        int cnt = rd16(moov, q + 1);
                        q += 3;
                        for (int i = 0; i < cnt; i++) {
                            int len = rd16(moov, q);
                            byte[] nal = Arrays.copyOfRange(moov, q + 2, q + 2 + len);
                            if (type == 32 && prm.vps == null) prm.vps = nal;
                            if (type == 33 && prm.sps == null) prm.sps = nal;
                            if (type == 34 && prm.pps == null) prm.pps = nal;
                            q += 2 + len;
                        }
                    }
                }
            }
            if (prm.sps == null || prm.pps == null)
                throw new RecoveryException("V referencnim videu chybi nastaveni kodeku (SPS/PPS).");
            try {
                if (hevc) {
                    parseHevcSps(prm);
                    parseHevcPps(prm);
                } else {
                    parseAvcSps(prm);
                    parseAvcPps(prm);
                }
            } catch (RuntimeException ex) {
                throw new RecoveryException("Nastaveni kodeku v referencnim videu nejde precist.");
            }
            prm.source = "z referencniho videa";
            return prm;
        }
        throw new RecoveryException("V referencnim videu jsem nenasel obrazovou stopu.");
    }

    // =====================================================================
    // Prochazeni dat a skladani snimku
    // =====================================================================

    static final class Nal {
        int type, refIdc, tid;
        long len; // bez delkoveho prefixu
    }

    static final class Frames {
        long[] off = new long[1024];
        int[] size = new int[1024];
        boolean[] key = new boolean[1024];
        int[] poc = new int[1024];
        int[] period = new int[1024];
        int n = 0;

        void add(long o, int s, boolean k, int pc, int per) {
            if (n == off.length) {
                int m = n * 2;
                off = Arrays.copyOf(off, m);
                size = Arrays.copyOf(size, m);
                key = Arrays.copyOf(key, m);
                poc = Arrays.copyOf(poc, m);
                period = Arrays.copyOf(period, m);
            }
            off[n] = o;
            size[n] = s;
            key[n] = k;
            poc[n] = pc;
            period[n] = per;
            n++;
        }
    }

    static final class Walker {
        final Src s;
        final Params p;
        final long end;
        final int ls;
        long skipped = 0;
        int totalMbs;
        final int maxFrameNum;
        final boolean extendedProfile;

        // stav dekoderu
        int prevPocMsb = 0, prevPocLsb = 0;
        int lastRefFrameNum = 0;
        int lastPoc = 0;
        boolean haveState = false;
        int period = -1;
        int decodeIdx = 0;
        boolean first = true;
        int framesSeen = 0;
        long maxAu = 0;
        boolean multiSliceSeen = false;
        boolean needKey = false;
        long maxGapSeen = 0;
        int gapsSeen = 0;

        // rozpracovany snimek
        long auStart = -1;
        long auEnd = -1;
        boolean auHasVcl = false;
        boolean auKey = false;
        int auPoc = 0;
        int auLastFirstMb = -1;
        boolean auField = false, auBottom = false;
        int auFrameNum = -1;
        int auSliceType = -1;
        int auPocLsb = 0;
        long auFirstSliceLen = 0;
        int auSlices = 0;

        final Frames out = new Frames();
        boolean seenKey = false;

        Walker(Src s, Params p, long end) {
            this.s = s;
            this.p = p;
            this.end = end;
            this.ls = p.lengthSize;
            this.maxFrameNum = p.hevc ? 0 : (1 << p.log2MaxFrameNum);
            this.extendedProfile = !p.hevc && (p.sps[1] & 0xFF) == 88;
            if (!p.hevc) totalMbs = ((p.width + 15) / 16) * ((p.height + 15) / 16) * (p.frameMbsOnly ? 1 : 2);
        }

        long readLen(long pos) throws IOException {
            long v = 0;
            for (int i = 0; i < ls; i++) v = (v << 8) | s.u8(pos + i);
            return v;
        }

        /** Precte hlavicku NAL na pozici; null kdyz to nevypada jako platna NAL jednotka. */
        Nal nalAt(long pos) throws IOException {
            if (pos + ls + 2 > end) return null;
            if (ls == 4 && s.u8(pos) != 0) return null; // > 16 MB na jednu NAL je nesmysl
            long len = readLen(pos);
            if (len < 2 || len > (32L << 20) || pos + ls + len > end) return null;
            Nal n = new Nal();
            n.len = len;
            int h = s.u8(pos + ls);
            if ((h & 0x80) != 0) return null;
            if (!p.hevc) {
                n.type = h & 0x1F;
                n.refIdc = (h >> 5) & 3;
                int t = n.type;
                if (t == 0 || (t >= 13 && t <= 18) || t >= 21) return null;
                if (t >= 2 && t <= 4 && !extendedProfile) return null; // datove oddily jen v Extended profilu
                if ((t == 5 || t == 7 || t == 8) && n.refIdc == 0) return null;
                if ((t == 6 || t == 9 || t == 10 || t == 11 || t == 12) && n.refIdc != 0) return null;
            } else {
                int h2 = s.u8(pos + ls + 1);
                n.type = (h >> 1) & 0x3F;
                int layer = ((h & 1) << 5) | (h2 >> 3);
                n.tid = (h2 & 7) - 1;
                if (layer != 0 || n.tid < 0 || n.tid > p.maxSubLayers) return null;
                int t = n.type;
                if (!((t <= 9) || (t >= 16 && t <= 21) || (t >= 32 && t <= 40))) return null;
            }
            return n;
        }

        boolean isVcl(Nal n) {
            return p.hevc ? n.type < 32 : (n.type >= 1 && n.type <= 5);
        }

        /** Vysledek parsovani hlavicky rezu. */
        final class Slice {
            int firstMb; // AVC: first_mb_in_slice; HEVC: 0 = prvni rez snimku, jinak adresa+1
            int pocLsb;
            boolean idr, key;
            boolean field, bottom;
            int frameNum;
            int sliceType = -1;
            boolean ok;
        }

        Slice parseSlice(long pos, Nal n) throws IOException {
            Slice sl = new Slice();
            byte[] head = s.bytes(pos + ls, (int) Math.min(n.len, 96));
            try {
                if (!p.hevc) {
                    Bits b = new Bits(head, 1);
                    sl.firstMb = b.ue();
                    sl.sliceType = b.ue();
                    int ppsId = b.ue();
                    if (sl.sliceType > 9 || ppsId != p.ppsId) return sl;
                    if (totalMbs > 0 && sl.firstMb >= totalMbs) return sl;
                    if (p.sepColour) b.u(2);
                    sl.frameNum = b.ui(p.log2MaxFrameNum);
                    if (!p.frameMbsOnly) {
                        sl.field = b.bit() == 1;
                        if (sl.field) sl.bottom = b.bit() == 1;
                    }
                    sl.idr = n.type == 5;
                    sl.key = sl.idr;
                    if (sl.idr) {
                        if (sl.frameNum != 0) return sl;
                        b.ue();
                    }
                    if (p.pocType == 0) sl.pocLsb = b.ui(p.log2MaxPocLsb);
                    if (p.avcDeep && !deepAvcCheck(b, sl, n)) return sl;
                } else {
                    Bits b = new Bits(head, 2);
                    boolean firstSlice = b.bit() == 1;
                    if (n.type >= 16 && n.type <= 23) b.bit();
                    int ppsId = b.ue();
                    if (ppsId != p.ppsId) return sl;
                    sl.key = n.type >= 16 && n.type <= 21;
                    sl.idr = n.type == 19 || n.type == 20;
                    if (firstSlice) {
                        sl.firstMb = 0;
                        b.u(p.numExtraSliceBits);
                        sl.sliceType = b.ue();
                        if (sl.sliceType > 2) return sl;
                        if (p.outputFlagPresent) b.bit();
                        if (p.sepColour) b.u(2);
                        if (!sl.idr) sl.pocLsb = b.ui(p.log2MaxPocLsb);
                    } else {
                        if (p.ctbAddrBits <= 0) return sl;
                        if (p.dependentSliceEnabled) b.bit();
                        int addr = b.ui(p.ctbAddrBits);
                        if (addr == 0 || addr >= p.picSizeInCtbs) return sl;
                        sl.firstMb = addr;
                    }
                }
                sl.ok = true;
            } catch (RuntimeException e) {
                sl.ok = false;
            }
            return sl;
        }

        /** Pokracuje v parsovani hlavicky H.264 rezu az ke QP; vraci false, kdyz hodnoty nedavaji smysl. */
        boolean deepAvcCheck(Bits b, Slice sl, Nal n) {
            if (p.pocType == 0 && p.bottomFieldPicOrder && !sl.field) b.se();
            if (p.pocType == 1 && !p.deltaPicOrderAlwaysZero) {
                b.se();
                if (p.bottomFieldPicOrder && !sl.field) b.se();
            }
            if (p.redundantPicCnt && b.ue() > 127) return false;
            int st = sl.sliceType % 5; // 0 P, 1 B, 2 I, 3 SP, 4 SI
            if (sl.idr && st != 2 && st != 4) return false;
            if (st == 1) b.bit();
            int l0 = p.numRefL0, l1 = p.numRefL1;
            if (st == 0 || st == 1 || st == 3) {
                if (b.bit() == 1) {
                    l0 = b.ue() + 1;
                    if (l0 > 32) return false;
                    if (st == 1) {
                        l1 = b.ue() + 1;
                        if (l1 > 32) return false;
                    }
                }
            }
            for (int list = 0; list < (st == 1 ? 2 : (st == 2 || st == 4 ? 0 : 1)); list++) {
                if (b.bit() == 1) {
                    int k = 0;
                    while (true) {
                        int idc = b.ue();
                        if (idc > 5) return false;
                        if (idc == 3) break;
                        b.ue();
                        if (++k > 33) return false;
                    }
                }
            }
            if ((p.weightedPred && (st == 0 || st == 3)) || (p.weightedBipred == 1 && st == 1)) return true;
            if (n.refIdc != 0) {
                if (sl.idr) {
                    b.bit();
                    b.bit();
                } else if (b.bit() == 1) {
                    int k = 0;
                    while (true) {
                        int op = b.ue();
                        if (op > 6) return false;
                        if (op == 0) break;
                        if (op == 1 || op == 3) b.ue();
                        if (op == 2) b.ue();
                        if (op == 3 || op == 6) b.ue();
                        if (op == 4) b.ue();
                        if (++k > 66) return false;
                    }
                }
            }
            if (p.entropyCabac && st != 2 && st != 4) {
                if (b.ue() > 2) return false;
            }
            int qp = p.picInitQp + b.se();
            int maxQp = 51 + 6 * (p.bitDepthLuma - 8);
            return qp >= -6 * (p.bitDepthLuma - 8) && qp <= maxQp;
        }

        // ---------- POC ----------

        private int pocMsbFor(int lsb, boolean reset) {
            if (reset) return 0;
            int max = 1 << p.log2MaxPocLsb;
            if (lsb < prevPocLsb && prevPocLsb - lsb >= max / 2) return prevPocMsb + max;
            if (lsb > prevPocLsb && lsb - prevPocLsb > max / 2) return prevPocMsb - max;
            return prevPocMsb;
        }

        boolean resetsPeriod(Slice sl, Nal n) {
            if (!p.hevc) return sl.idr;
            int t = n.type;
            return sl.idr || (t >= 16 && t <= 18) || (t == 21 && first);
        }

        /** POC bez zmeny stavu (pro kontrolu). */
        int peekPoc(Slice sl, Nal n) {
            if (!p.hevc && p.pocType != 0) return (decodeIdx + 1) * 2;
            boolean reset = resetsPeriod(sl, n);
            return pocMsbFor(sl.pocLsb, reset) + sl.pocLsb;
        }

        int commitPoc(Slice sl, Nal n) {
            if (!p.hevc) {
                if (p.pocType != 0) return decodeIdx * 2;
                if (sl.idr) {
                    prevPocMsb = 0;
                    prevPocLsb = 0;
                }
                int msb = pocMsbFor(sl.pocLsb, false);
                if (n.refIdc != 0) {
                    prevPocMsb = msb;
                    prevPocLsb = sl.pocLsb;
                }
                return msb + sl.pocLsb;
            } else {
                int t = n.type;
                int msb = pocMsbFor(sl.pocLsb, resetsPeriod(sl, n));
                boolean subLayerNonRef = t <= 14 && (t % 2 == 0);
                boolean radlRasl = t >= 6 && t <= 9;
                if (n.tid == 0 && !radlRasl && !subLayerNonRef) {
                    prevPocMsb = msb;
                    prevPocLsb = sl.pocLsb;
                }
                return msb + sl.pocLsb;
            }
        }

        // ---------- kontroly vierohodnosti ----------

        /** Je tento rez vierohodny zacatek noveho snimku? */
        boolean plausibleNewPicture(Slice sl, Nal n, boolean strict) {
            if (sl.firstMb != 0) return false;
            if (framesSeen >= 3 && n.len > Math.max(4 * maxAu, 512L << 10)) return false;
            if (!strict || !haveState) return true;
            if (resetsPeriod(sl, n)) return true;
            if (!p.hevc) {
                int fn = sl.frameNum;
                if (fn != lastRefFrameNum && fn != (lastRefFrameNum + 1) % maxFrameNum) return false;
                if (p.pocType == 0 && Math.abs(peekPoc(sl, n) - lastPoc) > 512) return false;
            } else {
                if (Math.abs(peekPoc(sl, n) - lastPoc) > 512) return false;
            }
            return true;
        }

        /** Je tento rez vierohodne pokracovani rozpracovaneho snimku? */
        boolean plausibleContinuation(Slice sl, Nal n) {
            if (!auHasVcl || sl.firstMb <= auLastFirstMb) return false;
            if (n.len > 8 * auFirstSliceLen + (256 << 10)) return false;
            if (!p.hevc) {
                if (sl.frameNum != auFrameNum) return false;
                if (sl.sliceType % 5 != auSliceType % 5) return false;
                if (p.pocType == 0 && sl.pocLsb != auPocLsb) return false;
                if (sl.field != auField) return false;
            }
            return true;
        }

        /** Je na pozici pravdepodobne zacatek obrazovych dat? (po useku zvuku) */
        boolean strongStart(long pos, boolean strict, boolean requireChain) throws IOException {
            Nal n = nalAt(pos);
            if (n == null) return false;
            long next = pos + ls + n.len;
            Nal n2 = next < end ? nalAt(next) : null;
            if (isVcl(n)) {
                Slice sl = parseSlice(pos, n);
                if (!sl.ok || !plausibleNewPicture(sl, n, strict)) return false;
                if (!strict && !sl.key) return false; // po poskozeni navazuj jen klicovym snimkem
                if (n2 != null || next == end) return true;
                return !requireChain && n.len >= 16;
            } else {
                int t = n.type;
                boolean useful = p.hevc ? (t >= 32 && t <= 40) : (t >= 6 && t <= 9);
                if (!useful || n2 == null) return false;
                if (!strict) {
                    // bez navaznosti: za ne-VCL musi do par NAL prijit klicovy snimek
                    long q = next;
                    for (int k = 0; k < 8; k++) {
                        Nal m = nalAt(q);
                        if (m == null) return false;
                        if (isVcl(m)) {
                            Slice sl = parseSlice(q, m);
                            return sl.ok && sl.key && sl.firstMb == 0;
                        }
                        q += ls + m.len;
                    }
                    return false;
                }
                return true;
            }
        }

        /** Jak daleko za koncem snimku muze nejdal zacit dalsi (mezi nimi je zvuk). Uci se z dat. */
        long gapLimit() {
            if (gapsSeen < 8) return 1L << 20;
            return Math.min(1L << 20, Math.max(8L << 10, 4 * maxGapSeen + 4096));
        }

        /** Zakladni udaje o snimku pro kontrolu navaznosti. */
        static final class Pic {
            int fn, lsb;
            boolean ref, key, idr;
        }

        /** Na pozici r zacina snimek (pripadne s ne-VCL NAL pred nim)? Vrati jeho udaje, jinak null. */
        Pic picAt(long r) throws IOException {
            long q = r;
            for (int k = 0; k < 8; k++) {
                Nal n = nalAt(q);
                if (n == null) return null;
                if (isVcl(n)) {
                    Slice sl = parseSlice(q, n);
                    if (!sl.ok || sl.firstMb != 0) return null;
                    if (framesSeen >= 3 && n.len > Math.max(4 * maxAu, 512L << 10)) return null;
                    Pic pc = new Pic();
                    pc.fn = sl.frameNum;
                    pc.lsb = sl.pocLsb;
                    pc.ref = p.hevc || n.refIdc != 0;
                    pc.key = sl.key;
                    pc.idr = resetsPeriod(sl, n);
                    return pc;
                }
                int t = n.type;
                boolean useful = p.hevc ? (t >= 32 && t <= 40) : (t >= 6 && t <= 9);
                if (!useful) return null;
                q += ls + n.len;
                if (q >= end) return null;
            }
            return null;
        }

        /** Navazuje snimek b na snimek a? */
        boolean follows(Pic a, Pic b) {
            if (b.idr) return true;
            if (!p.hevc) {
                int expect = a.ref ? (a.fn + 1) % maxFrameNum : a.fn;
                return b.fn == expect;
            }
            int max = 1 << p.log2MaxPocLsb;
            int d = Math.floorMod(b.lsb - a.lsb, max);
            return d <= 64 || d >= max - 64 || b.key;
        }

        /**
         * Overi, ze kandidat na pozici q je skutecne video: projde navazujici NAL, a kde retez skonci
         * (zacina zvuk), musi do rozumne vzdalenosti najit dalsi snimek, ktery na nej logicky navazuje.
         * Zvuk, ktery jen nahodou vypada jako video, tohle temer nikdy nesplni.
         */
        boolean verifyAhead(long q, int depth) throws IOException {
            long pos = q;
            Pic last = null;
            while (true) {
                Nal n = nalAt(pos);
                if (n == null) break;
                if (isVcl(n)) {
                    Slice sl = parseSlice(pos, n);
                    if (!sl.ok) return false;
                    if (sl.firstMb == 0) {
                        Pic pc = new Pic();
                        pc.fn = sl.frameNum;
                        pc.lsb = sl.pocLsb;
                        pc.ref = p.hevc || n.refIdc != 0;
                        pc.key = sl.key;
                        pc.idr = resetsPeriod(sl, n);
                        if (last != null && !follows(last, pc)) return false;
                        last = pc;
                    }
                }
                long next = pos + ls + n.len;
                if (next >= end) return true;
                pos = next;
                if (nalAt(pos) == null) break;
                if (pos - q > (32L << 20)) return true;
            }
            if (last == null) return false;
            long limit = Math.min(end, pos + gapLimit());
            for (long r = pos; r < limit; r++) {
                Pic nx = picAt(r);
                if (nx == null || !follows(last, nx)) continue;
                if (depth <= 1 || verifyAhead(r, depth - 1)) return true;
            }
            return limit >= end;
        }

        void closeAu() {
            if (auHasVcl && auStart >= 0 && auEnd > auStart) {
                if (auKey) seenKey = true;
                if (needKey && auKey) needKey = false;
                if (seenKey && !needKey && auEnd - auStart < Integer.MAX_VALUE) {
                    out.add(auStart, (int) (auEnd - auStart), auKey, auPoc, period);
                }
                framesSeen++;
                maxAu = Math.max(maxAu, auEnd - auStart);
                if (auSlices > 1) multiSliceSeen = true;
            }
            auStart = -1;
            auEnd = -1;
            auHasVcl = false;
            auKey = false;
            auLastFirstMb = -1;
            auSlices = 0;
        }

        void run(long start, Listener l) throws IOException {
            long pos = start;
            long pendingStart = -1; // zacatek ne-VCL NAL pred prvnim rezem
            long reportStep = Math.max(1, (end - start) / 60);
            long nextReport = start + reportStep;

            while (pos < end) {
                if (pos >= nextReport) {
                    if (l != null) l.progress(10 + (int) (60 * (pos - start) / Math.max(1, end - start)));
                    nextReport += reportStep;
                }
                Nal n = nalAt(pos);
                boolean valid = n != null;
                Slice sl = null;
                boolean newPicture = false;
                if (valid) {
                    long next = pos + ls + n.len;
                    boolean chained = next == end || nalAt(next) != null;
                    if (isVcl(n)) {
                        sl = parseSlice(pos, n);
                        if (!sl.ok) valid = false;
                        else if (sl.firstMb == 0) {
                            newPicture = true;
                            if (!plausibleNewPicture(sl, n, true)) valid = false;
                        } else {
                            if (!plausibleContinuation(sl, n)) valid = false;
                            else if (!chained && !multiSliceSeen && framesSeen >= 3) valid = false;
                        }
                        if (valid && !chained && !verifyAhead(pos, 2)) valid = false;
                    } else if (!chained) {
                        valid = false;
                    }
                }

                if (!valid) {
                    // zvuk nebo smeti: uzavri snimek a najdi dalsi obrazova data
                    pendingStart = -1;
                    closeAu();
                    long q = pos + 1;
                    long relaxAt = pos + Math.max(16L << 20, 50 * maxAu);
                    boolean found = false;
                    while (q < end) {
                        if (strongStart(q, true, false) && verifyAhead(q, 2)) {
                            found = true;
                            break;
                        }
                        if (q >= relaxAt) break;
                        q++;
                    }
                    if (found && gapsSeen >= 8 && q - pos > gapLimit()) {
                        // velka dira = chybejici/poskozena data: navaz az od klicoveho snimku (bez artefaktu)
                        needKey = true;
                    }
                    if (found && q - pos <= (1L << 20)) {
                        maxGapSeen = Math.max(maxGapSeen, q - pos);
                        gapsSeen++;
                    }
                    if (!found && q < end) {
                        // dlouho nic: video je asi poskozene - navaz nejblizsim klicovym snimkem
                        q = pos + 1;
                        while (q < end && !strongStart(q, false, false)) q++;
                        haveState = false;
                        needKey = true;
                    }
                    if (DEBUG) System.err.println("SKIP " + pos + " -> " + q);
                    skipped += q - pos;
                    pos = q;
                    continue;
                }

                long next = pos + ls + n.len;
                if (DEBUG) System.err.println("NAL @" + pos + " len=" + n.len + " type=" + n.type
                        + (sl != null ? " firstMb=" + sl.firstMb + " fn=" + sl.frameNum + " st=" + sl.sliceType : ""));
                if (isVcl(n)) {
                    if (newPicture && !p.hevc && sl.field && auHasVcl && auField && !auBottom == sl.bottom
                            && auFrameNum == sl.frameNum && pendingStart < 0) {
                        newPicture = false; // druhe pole stejneho snimku
                        auField = false;
                    }
                    if (newPicture) {
                        closeAu();
                        auStart = pendingStart >= 0 ? pendingStart : pos;
                        pendingStart = -1;
                        if (resetsPeriod(sl, n) || period < 0) period++;
                        auPoc = commitPoc(sl, n);
                        lastPoc = auPoc;
                        if (!p.hevc && (n.refIdc != 0)) lastRefFrameNum = sl.frameNum;
                        if (!p.hevc && sl.idr) lastRefFrameNum = 0;
                        haveState = true;
                        auKey = sl.key;
                        auField = sl.field;
                        auBottom = sl.bottom;
                        auFrameNum = sl.frameNum;
                        auSliceType = sl.sliceType;
                        auPocLsb = sl.pocLsb;
                        auFirstSliceLen = n.len;
                        decodeIdx++;
                        first = false;
                    } else if (pendingStart >= 0) {
                        pendingStart = -1;
                    }
                    auHasVcl = true;
                    auSlices++;
                    if (sl.key) auKey = true;
                    auLastFirstMb = sl.firstMb;
                    auEnd = next;
                } else {
                    if (pendingStart < 0) pendingStart = pos;
                    if (!p.hevc && (n.type == 10 || n.type == 11) && auHasVcl) {
                        auEnd = next;
                        pendingStart = -1;
                    }
                }
                pos = next;
            }
            closeAu();
        }
    }

    // =====================================================================
    // Zapis MP4
    // =====================================================================

    static final class W {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(bo);

        W u8(int v) throws IOException {
            d.writeByte(v);
            return this;
        }

        W u16(int v) throws IOException {
            d.writeShort(v);
            return this;
        }

        W u32(long v) throws IOException {
            d.writeInt((int) v);
            return this;
        }

        W u64(long v) throws IOException {
            d.writeLong(v);
            return this;
        }

        W bytes(byte[] b) throws IOException {
            d.write(b);
            return this;
        }

        W str(String s) throws IOException {
            d.write(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            return this;
        }

        W full(int version, int flags) throws IOException {
            return u32(((long) version << 24) | flags);
        }

        byte[] bytes() {
            return bo.toByteArray();
        }
    }

    static byte[] box(String type, byte[]... parts) throws IOException {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        W w = new W().u32(len).str(type);
        for (byte[] p : parts) w.bytes(p);
        return w.bytes();
    }

    private static final int[] MATRIX = {0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000};

    static byte[] avcC(Params p) throws IOException {
        W w = new W().u8(1).u8(p.sps[1] & 0xFF).u8(p.sps[2] & 0xFF).u8(p.sps[3] & 0xFF)
                .u8(0xFC | (p.lengthSize - 1)).u8(0xE1).u16(p.sps.length).bytes(p.sps)
                .u8(1).u16(p.pps.length).bytes(p.pps);
        int profile = p.sps[1] & 0xFF;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            w.u8(0xFC | (p.chromaFormat & 3)).u8(0xF8 | ((p.bitDepthLuma - 8) & 7))
                    .u8(0xF8 | ((p.bitDepthChroma - 8) & 7)).u8(0);
        }
        return box("avcC", w.bytes());
    }

    static byte[] hvcC(Params p) throws IOException {
        byte[] spsRbsp = Bits.unescape(p.sps, 2);
        // profile_tier_level general: 12 bajtu po prvnim bajtu SPS
        W w = new W().u8(1);
        for (int i = 1; i <= 12; i++) w.u8(i < spsRbsp.length ? spsRbsp[i] & 0xFF : 0);
        w.u16(0xF000).u8(0xFC).u8(0xFC | (p.chromaFormat & 3))
                .u8(0xF8 | ((p.bitDepthLuma - 8) & 7)).u8(0xF8 | ((p.bitDepthChroma - 8) & 7))
                .u16(0).u8(((p.maxSubLayers + 1) << 3) | (1 << 2) | (p.lengthSize - 1));
        w.u8(3);
        byte[][] nals = {p.vps, p.sps, p.pps};
        int[] types = {32, 33, 34};
        for (int i = 0; i < 3; i++) {
            w.u8(0x80 | types[i]).u16(1).u16(nals[i].length).bytes(nals[i]);
        }
        return box("hvcC", w.bytes());
    }

    static byte[] sampleEntry(Params p) throws IOException {
        if (p.sampleEntry != null) return p.sampleEntry;
        W w = new W();
        w.u32(0).u16(0).u16(1); // reserved, data_reference_index
        w.u16(0).u16(0).u32(0).u32(0).u32(0);
        w.u16(p.width).u16(p.height).u32(0x00480000).u32(0x00480000).u32(0).u16(1);
        byte[] name = new byte[32];
        w.bytes(name).u16(0x18).u16(0xFFFF);
        w.bytes(p.hevc ? hvcC(p) : avcC(p));
        return box(p.hevc ? "hvc1" : "avc1", w.bytes());
    }

    // =====================================================================
    // Hlavni funkce
    // =====================================================================

    /**
     * @param broken    rozbite video (jen cteni)
     * @param reference funkcni video ze stejneho zdroje, nebo null
     * @param out       vystupni soubor (zapis)
     * @param fps       vynucena snimkova frekvence, nebo 0 = automaticky
     */
    public static Result recover(FileChannel broken, FileChannel reference, FileChannel out, double fps, Listener l)
            throws IOException, RecoveryException {
        Src s = new Src(broken);
        if (s.size < 64) throw new RecoveryException("Soubor je prilis maly.");

        // 1) najdi mdat
        long mdatStart = -1, mdatEnd = -1;
        boolean hasMoov = false;
        long p = 0;
        while (p + 8 <= s.size) {
            long size = s.u32(p);
            String t = s.type(p + 4);
            int hdr = 8;
            if (size == 1) {
                if (p + 16 > s.size) break;
                size = s.u64(p + 8);
                hdr = 16;
            } else if (size == 0) size = s.size - p;
            if (t.equals("moov")) hasMoov = true;
            if (t.equals("mdat") && mdatStart < 0) {
                mdatStart = p + hdr;
                mdatEnd = Math.min(s.size, p + size);
            }
            if (size < 8) break;
            p += size;
        }
        if (mdatStart < 0) {
            // nekdy chybi i hlavicky - zkus data od zacatku
            mdatStart = 0;
            mdatEnd = s.size;
        }
        if (l != null) l.progress(1);

        // 2) nastaveni kodeku
        Params prm = findInBandParams(s, mdatStart, mdatEnd, l);
        Params ref = reference != null ? paramsFromReference(new Src(reference)) : null;
        if (prm == null && ref == null) {
            throw new RecoveryException(
                    "Nastaveni kodeku v rozbitem souboru neni. Vyber referencni video - jine funkcni video "
                            + "ze stejneho zdroje (stejny telefon/aplikace, stejny web nebo kanal).");
        }
        if (prm == null) prm = ref;
        else if (ref != null) {
            // casovani z reference, jinak parametry z dat (presnejsi)
            if (ref.hevc == prm.hevc) {
                prm.refTimescale = ref.refTimescale;
                prm.refDelta = ref.refDelta;
                prm.lengthSize = ref.lengthSize;
            }
            prm.source = "z dat videa + casovani z reference";
        }

        // 3) projdi data
        Walker w = new Walker(s, prm, mdatEnd);
        w.run(mdatStart, l);
        Frames f = w.out;
        if (f.n < 2) {
            throw new RecoveryException(ref != null && prm == ref
                    ? "Referencni video k tomuhle souboru nepasuje (jiny kodek nebo nastaveni). Zkus jine ze stejneho zdroje."
                    : "V souboru jsem nenasel pouzitelne snimky obrazu.");
        }

        // 4) casovani
        long timescale, delta;
        if (fps > 0) {
            timescale = Math.round(fps * 1000);
            delta = 1000;
        } else if (prm.refTimescale > 0 && prm.refDelta > 0) {
            timescale = prm.refTimescale;
            delta = prm.refDelta;
        } else if (prm.vuiScale > 0 && prm.vuiUnits > 0 && !prm.hevc) {
            timescale = prm.vuiScale;
            delta = 2 * prm.vuiUnits;
            double vf = (double) timescale / delta;
            if (vf < 5 || vf > 240) {
                timescale = 30000;
                delta = 1000;
            }
        } else {
            timescale = 30000;
            delta = 1000;
        }

        // poradi zobrazeni (B-snimky)
        int n = f.n;
        int[] pts = new int[n];
        int i0 = 0;
        while (i0 < n) {
            int i1 = i0;
            while (i1 < n && f.period[i1] == f.period[i0]) i1++;
            Integer[] idx = new Integer[i1 - i0];
            for (int k = 0; k < idx.length; k++) idx[k] = i0 + k;
            final int[] poc = f.poc;
            Arrays.sort(idx, (a, b) -> poc[a] != poc[b] ? Integer.compare(poc[a], poc[b]) : Integer.compare(a, b));
            for (int r = 0; r < idx.length; r++) pts[idx[r]] = i0 + r;
            i0 = i1;
        }
        int delay = 0;
        boolean reordered = false;
        for (int i = 0; i < n; i++) {
            delay = Math.max(delay, i - pts[i]);
            if (pts[i] != i) reordered = true;
        }

        // 5) zapis
        long mediaDur = n * delta;
        long movieTs = 1000;
        long movieDur = mediaDur * movieTs / timescale;
        int keyCount = 0;
        for (int i = 0; i < n; i++) if (f.key[i]) keyCount++;

        // souvisle useky dat (pro rychle kopirovani)
        byte[] ftyp = box("ftyp", new W().str("isom").u32(0x200).str("isomiso2avc1mp41").bytes());

        byte[] moov0 = buildMoov(prm, f, pts, delay, reordered, timescale, delta, mediaDur, movieTs, movieDur, keyCount, 0);
        long dataBase = ftyp.length + moov0.length + 16;
        byte[] moov = buildMoov(prm, f, pts, delay, reordered, timescale, delta, mediaDur, movieTs, movieDur, keyCount, dataBase);

        long dataLen = 0;
        for (int i = 0; i < n; i++) dataLen += f.size[i];

        out.position(0);
        writeFully(out, ftyp);
        writeFully(out, moov);
        writeFully(out, new W().u32(1).str("mdat").u64(16 + dataLen).bytes());
        long written = 0;
        int i = 0;
        while (i < n) {
            long o = f.off[i];
            long len = f.size[i];
            int j = i + 1;
            while (j < n && f.off[j] == o + len && len < (64L << 20)) {
                len += f.size[j];
                j++;
            }
            long done = 0;
            while (done < len) {
                long t = broken.transferTo(o + done, len - done, out);
                if (t <= 0) throw new IOException("Kopirovani dat selhalo");
                done += t;
            }
            written += len;
            if (l != null) l.progress(70 + (int) (29 * written / Math.max(1, dataLen)));
            i = j;
        }
        out.truncate(out.position());
        out.force(false);
        if (l != null) l.progress(100);

        Result r = new Result();
        r.codec = prm.hevc ? "H.265" : "H.264";
        r.width = prm.width;
        r.height = prm.height;
        r.frames = n;
        r.keyframes = keyCount;
        r.fps = (double) timescale / delta;
        r.seconds = (double) mediaDur / timescale;
        r.skippedBytes = w.skipped;
        r.configSource = prm.source;
        r.bFrames = reordered;
        return r;
    }

    private static void writeFully(FileChannel ch, byte[] b) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b);
        while (bb.hasRemaining()) ch.write(bb);
    }

    private static byte[] buildMoov(Params prm, Frames f, int[] pts, int delay, boolean reordered,
                                    long timescale, long delta, long mediaDur, long movieTs, long movieDur,
                                    int keyCount, long dataBase) throws IOException {
        int n = f.n;

        W mvhd = new W().full(0, 0).u32(0).u32(0).u32(movieTs).u32(movieDur).u32(0x00010000).u16(0x0100)
                .u16(0).u32(0).u32(0);
        for (int m : MATRIX) mvhd.u32(m);
        for (int k = 0; k < 6; k++) mvhd.u32(0);
        mvhd.u32(2);

        W tkhd = new W().full(0, 3).u32(0).u32(0).u32(1).u32(0).u32(movieDur).u32(0).u32(0)
                .u16(0).u16(0).u16(0).u16(0);
        for (int m : MATRIX) tkhd.u32(m);
        tkhd.u32((long) prm.width << 16).u32((long) prm.height << 16);

        byte[] edts = new byte[0];
        if (delay > 0) {
            W elst = new W().full(0, 0).u32(1).u32(movieDur).u32(delay * delta).u16(1).u16(0);
            edts = box("edts", box("elst", elst.bytes()));
        }

        W mdhd = new W().full(0, 0).u32(0).u32(0).u32(timescale).u32(mediaDur).u16(0x55C4).u16(0);
        W hdlr = new W().full(0, 0).u32(0).str("vide").u32(0).u32(0).u32(0).str("VideoHandler").u8(0);
        W vmhd = new W().full(0, 1).u16(0).u16(0).u16(0).u16(0);
        byte[] dinf = box("dinf", box("dref", new W().full(0, 0).u32(1).bytes(), box("url ", new W().full(0, 1).bytes())));

        byte[] stsd = box("stsd", new W().full(0, 0).u32(1).bytes(), sampleEntry(prm));
        byte[] stts = box("stts", new W().full(0, 0).u32(1).u32(n).u32(delta).bytes());

        byte[] ctts = new byte[0];
        if (reordered) {
            W c = new W();
            int runs = 0;
            int k = 0;
            while (k < n) {
                long off = (long) (pts[k] - k + delay) * delta;
                int m = k + 1;
                while (m < n && (long) (pts[m] - m + delay) * delta == off) m++;
                c.u32(m - k).u32(off);
                runs++;
                k = m;
            }
            ctts = box("ctts", new W().full(0, 0).u32(runs).bytes(), c.bytes());
        }

        W ss = new W().full(0, 0).u32(keyCount);
        for (int k = 0; k < n; k++) if (f.key[k]) ss.u32(k + 1);
        byte[] stss = box("stss", ss.bytes());

        byte[] stsc = box("stsc", new W().full(0, 0).u32(1).u32(1).u32(1).u32(1).bytes());

        W sz = new W().full(0, 0).u32(0).u32(n);
        for (int k = 0; k < n; k++) sz.u32(f.size[k]);
        byte[] stsz = box("stsz", sz.bytes());

        W co = new W().full(0, 0).u32(n);
        long pos = dataBase;
        for (int k = 0; k < n; k++) {
            co.u64(pos);
            pos += f.size[k];
        }
        byte[] co64 = box("co64", co.bytes());

        byte[] stbl = box("stbl", stsd, stts, ctts, stss, stsc, stsz, co64);
        byte[] minf = box("minf", box("vmhd", vmhd.bytes()), dinf, stbl);
        byte[] mdia = box("mdia", box("mdhd", mdhd.bytes()), box("hdlr", hdlr.bytes()), minf);
        byte[] trak = box("trak", box("tkhd", tkhd.bytes()), edts, mdia);
        return box("moov", box("mvhd", mvhd.bytes()), trak);
    }
}
