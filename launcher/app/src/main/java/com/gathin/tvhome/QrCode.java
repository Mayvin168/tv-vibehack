package com.gathin.tvhome;

/**
 * Minimal QR Code generator (byte mode, error-correction level M, versions 1-10),
 * written for the phone-remote pairing dialog so the launcher needs no extra libraries.
 * Produces a module matrix; {@link #toBitmap} renders it.
 */
final class QrCode {

    // Data codeword capacity and block layout for EC level M, versions 1..10.
    // {total codewords, EC codewords per block, group1 blocks, group1 data cw each,
    //  group2 blocks, group2 data cw each, remainder bits}
    private static final int[][] M_TABLE = {
            {26, 10, 1, 16, 0, 0, 0},
            {44, 16, 1, 28, 0, 0, 7},
            {70, 26, 1, 44, 0, 0, 7},
            {100, 18, 2, 32, 0, 0, 7},
            {134, 24, 2, 43, 0, 0, 7},
            {172, 16, 4, 27, 0, 0, 7},
            {196, 18, 4, 31, 0, 0, 0},
            {242, 22, 2, 38, 2, 39, 0},
            {292, 22, 3, 36, 2, 37, 0},
            {346, 26, 4, 43, 1, 44, 0},
    };

    private static final int[][] ALIGNMENT = {
            {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
    };

    // Format bits for EC level M are computed (BCH) in drawFormat; version bits likewise.

    private final boolean[][] modules;
    private final int version;

    private QrCode(boolean[][] modules, int version) {
        this.modules = modules;
        this.version = version;
    }

    public int size() {
        return modules.length;
    }

    public boolean get(int x, int y) {
        return modules[y][x];
    }

    /** Encodes the text (UTF-8, byte mode) at EC level M, choosing the smallest fitting version. */
    public static QrCode encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (int v = 1; v <= 10; v++) {
            int dataCw = dataCodewords(v);
            int countBits = v <= 9 ? 8 : 16;
            int needed = 4 + countBits + data.length * 8;
            if (needed <= dataCw * 8) return build(data, v);
        }
        throw new IllegalArgumentException("text too long for QR");
    }

    private static int dataCodewords(int v) {
        int[] t = M_TABLE[v - 1];
        return t[2] * t[3] + t[4] * t[5];
    }

    private static QrCode build(byte[] data, int version) {
        int dataCw = dataCodewords(version);
        // Bit stream: mode 0100, count, data, terminator, pad.
        StringBuilder bits = new StringBuilder();
        bits.append("0100");
        int countBits = version <= 9 ? 8 : 16;
        for (int i = countBits - 1; i >= 0; i--) bits.append((data.length >>> i & 1) == 1 ? '1' : '0');
        for (byte b : data) {
            for (int i = 7; i >= 0; i--) bits.append((b >>> i & 1) == 1 ? '1' : '0');
        }
        int cap = dataCw * 8;
        int term = Math.min(4, cap - bits.length());
        for (int i = 0; i < term; i++) bits.append('0');
        while (bits.length() % 8 != 0) bits.append('0');
        int dataLen = bits.length() / 8;
        while (bits.length() < cap) bits.append('0');
        byte[] dataBytes = new byte[dataCw];
        for (int i = 0; i < dataCw; i++) {
            int val = 0;
            for (int j = 0; j < 8; j++) {
                val = val << 1 | (bits.charAt(i * 8 + j) == '1' ? 1 : 0);
            }
            dataBytes[i] = (byte) val;
        }
        for (int i = dataLen, k = 0; i < dataCw; i++, k++) {
            dataBytes[i] = (byte) (k % 2 == 0 ? 0xEC : 0x11);
        }

        byte[] all = addErrorCorrection(dataBytes, version);
        int remainder = M_TABLE[version - 1][6];
        StringBuilder stream = new StringBuilder();
        for (byte b : all) {
            for (int i = 7; i >= 0; i--) stream.append((b >>> i & 1) == 1 ? '1' : '0');
        }
        for (int i = 0; i < remainder; i++) stream.append('0');

        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = draw(version, stream, mask);
            int p = penalty(m);
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
        }
        return new QrCode(best, version);
    }

    // ---- Reed-Solomon over GF(256) -------------------------------------------------------------

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        return a == 0 || b == 0 ? 0 : GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static byte[] rsGenerator(int degree) {
        // Coefficients in descending order; multiply by (x + alpha^i) for i in 0..degree-1.
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int c = GF_EXP[i];
            int[] next = new int[poly.length + 1];
            next[0] = poly[0];
            for (int j = 1; j < poly.length; j++) {
                next[j] = poly[j] ^ gfMul(c, poly[j - 1]);
            }
            next[poly.length] = gfMul(c, poly[poly.length - 1]);
            poly = next;
        }
        byte[] out = new byte[degree];
        for (int i = 0; i < degree; i++) out[i] = (byte) poly[i + 1];
        return out;
    }

    private static byte[] rsRemainder(byte[] data, byte[] gen) {
        byte[] res = new byte[gen.length];
        for (byte b : data) {
            int factor = (b & 0xFF) ^ (res[0] & 0xFF);
            System.arraycopy(res, 1, res, 0, res.length - 1);
            res[res.length - 1] = 0;
            for (int i = 0; i < res.length; i++) {
                res[i] = (byte) ((res[i] & 0xFF) ^ gfMul(gen[i] & 0xFF, factor));
            }
        }
        return res;
    }

    private static byte[] addErrorCorrection(byte[] data, int version) {
        int[] t = M_TABLE[version - 1];
        int ecLen = t[1];
        byte[] gen = rsGenerator(ecLen);
        int nBlocks = t[2] + t[4];
        byte[][] blockData = new byte[nBlocks][];
        byte[][] blockEc = new byte[nBlocks][];
        int off = 0;
        for (int i = 0; i < nBlocks; i++) {
            int len = i < t[2] ? t[3] : t[5];
            blockData[i] = new byte[len];
            System.arraycopy(data, off, blockData[i], 0, len);
            blockEc[i] = rsRemainder(blockData[i], gen);
            off += len;
        }
        byte[] out = new byte[t[0]];
        int idx = 0;
        int maxLen = 0;
        for (byte[] b : blockData) maxLen = Math.max(maxLen, b.length);
        for (int i = 0; i < maxLen; i++) {
            for (byte[] b : blockData) if (i < b.length) out[idx++] = b[i];
        }
        for (int i = 0; i < ecLen; i++) {
            for (byte[] b : blockEc) out[idx++] = b[i];
        }
        return out;
    }

    // ---- matrix drawing --------------------------------------------------------------------------

    private static boolean[][] draw(int version, StringBuilder stream, int mask) {
        int size = version * 4 + 17;
        boolean[][] m = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];   // function modules

        // Timing patterns.
        for (int i = 0; i < size; i++) {
            setFn(m, fn, i, 6, i % 2 == 0);
            setFn(m, fn, 6, i, i % 2 == 0);
        }
        // Finder patterns + separators.
        drawFinder(m, fn, 3, 3);
        drawFinder(m, fn, size - 4, 3);
        drawFinder(m, fn, 3, size - 4);
        // Alignment patterns.
        int[] pos = ALIGNMENT[version - 1];
        for (int a : pos) {
            for (int b : pos) {
                if ((a == 6 && b == 6) || (a == 6 && b == size - 7) || (a == size - 7 && b == 6)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        setFn(m, fn, b + dx, a + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1 || (dx == 0 && dy == 0));
                    }
                }
            }
        }
        // Reserve format bits (drawn after masking) and the dark module.
        drawFormat(m, fn, mask);
        if (version >= 7) drawVersion(m, fn, version);
        setFn(m, fn, 8, size - 8, true);   // dark module

        // Place data bits in the zigzag.
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < stream.length()) {
                        boolean bit = stream.charAt(i++) == '1';
                        if (maskBit(mask, x, y)) bit = !bit;
                        m[y][x] = bit;
                    }
                }
            }
        }
        // Re-draw format (data placement must not overwrite it, but be safe).
        drawFormat(m, fn, mask);
        return m;
    }

    private static void setFn(boolean[][] m, boolean[][] fn, int x, int y, boolean val) {
        if (x < 0 || y < 0 || x >= m.length || y >= m.length) return;
        m[y][x] = val;
        fn[y][x] = true;
    }

    private static void drawFinder(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= m.length || y >= m.length) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(m, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private static void drawFormat(boolean[][] m, boolean[][] fn, int mask) {
        // EC level M has format indicator 00, so the 5 data bits are just the mask.
        int data = mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        int size = m.length;
        // First copy (top-left).
        for (int j = 0; j <= 5; j++) setFn(m, fn, 8, j, bit(bits, j));
        setFn(m, fn, 8, 7, bit(bits, 6));
        setFn(m, fn, 8, 8, bit(bits, 7));
        setFn(m, fn, 7, 8, bit(bits, 8));
        for (int j = 9; j <= 14; j++) setFn(m, fn, 14 - j, 8, bit(bits, j));
        // Second copy (split along the other two corners).
        for (int j = 0; j <= 7; j++) setFn(m, fn, size - 1 - j, 8, bit(bits, j));
        for (int j = 8; j <= 14; j++) setFn(m, fn, 8, size - 15 + j, bit(bits, j));
    }

    private static void drawVersion(boolean[][] m, boolean[][] fn, int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        int size = m.length;
        for (int i = 0; i < 18; i++) {
            setFn(m, fn, size - 11 + i % 3, i / 3, bit(bits, i));
            setFn(m, fn, i / 3, size - 11 + i % 3, bit(bits, i));
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        switch (mask) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return (x * y) % 2 + (x * y) % 3 == 0;
            case 6: return ((x * y) % 2 + (x * y) % 3) % 2 == 0;
            case 7: return ((x + y) % 2 + (x * y) % 3) % 2 == 0;
            default: return false;
        }
    }

    // ---- penalty scoring (spec rules, simplified weights) -----------------------------------------

    private static int penalty(boolean[][] m) {
        int size = m.length;
        int pen = 0;
        // Rule 1: runs of >=5 in rows/columns.
        for (int y = 0; y < size; y++) {
            pen += runsPenalty(m, y, true);
            pen += runsPenalty(m, y, false);
        }
        // Rule 2: 2x2 same-color blocks.
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) pen += 3;
            }
        }
        // Rule 3: finder-like patterns.
        for (int start = 0; start < size - 10; start++) {
            for (int line = 0; line < size; line++) {
                if (matches(m, line, start, true)) pen += 40;
                if (matches(m, line, start, false)) pen += 40;
            }
        }
        // Rule 4: dark ratio deviation.
        int dark = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int k = (int) Math.ceil(Math.abs(dark * 20 - total * 10) / (double) total) - 1;
        if (k > 0) pen += k * 10;
        return pen;
    }

    private static int runsPenalty(boolean[][] m, int fixed, boolean rowWise) {
        int size = m.length;
        int pen = 0, run = 1;
        for (int i = 1; i < size; i++) {
            boolean cur = rowWise ? m[fixed][i] : m[i][fixed];
            boolean prev = rowWise ? m[fixed][i - 1] : m[i - 1][fixed];
            if (cur == prev) {
                run++;
            } else {
                if (run >= 5) pen += 3 + (run - 5);
                run = 1;
            }
        }
        if (run >= 5) pen += 3 + (run - 5);
        return pen;
    }

    private static boolean matches(boolean[][] m, int a, int b, boolean rowWise) {
        // 1011101 preceded or followed by four light modules.
        String p1 = "00001011101", p2 = "10111010000";
        boolean ok1 = true, ok2 = true;
        for (int i = 0; i < 11; i++) {
            boolean v = rowWise ? m[a][b + i] : m[b + i][a];
            if (v != (p1.charAt(i) == '1')) ok1 = false;
            if (v != (p2.charAt(i) == '1')) ok2 = false;
        }
        return ok1 || ok2;
    }

    // ---- rendering ---------------------------------------------------------------------------------

    /** Renders the code with a 4-module quiet zone into an ARGB bitmap of the given pixel size. */
    public static android.graphics.Bitmap toBitmap(QrCode qr, int px) {
        int n = qr.size() + 8;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, px,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(0xFFFFFFFF);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(0xFF000000);
        float cell = px / (float) n;
        for (int y = 0; y < qr.size(); y++) {
            for (int x = 0; x < qr.size(); x++) {
                if (qr.get(x, y)) {
                    float l = (x + 4) * cell, t = (y + 4) * cell;
                    canvas.drawRect(l, t, l + cell + 0.5f, t + cell + 0.5f, paint);
                }
            }
        }
        return bmp;
    }
}
