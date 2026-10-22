package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.util.Base64;
import android.view.View;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;

/**
 * A tiny LAN web server that turns any phone browser into a TV remote.
 * Serves the remote page over HTTP and takes commands over a WebSocket,
 * authenticated by a pairing token (QR code) or a 4-digit code shown on the TV.
 */
final class RemoteServer {

    static final int PORT = 8899;
    private static final String PREFS = "tvhome";
    private static final String KEY_TOKEN = "remote_token";
    private static final String KEY_PIN = "remote_pin";
    private static final String TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static boolean started;
    private static Context appContext;

    private RemoteServer() {
    }

    static synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (started) return;
        started = true;
        Thread thread = new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket(PORT);
                while (true) {
                    Socket socket = server.accept();
                    Thread conn = new Thread(() -> handle(socket));
                    conn.setDaemon(true);
                    conn.start();
                }
            } catch (Exception ignored) {
                started = false;
            }
        }, "tv-remote-server");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- pairing secrets -------------------------------------------------------------------------

    static String token(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null) {
            SecureRandom random = new SecureRandom();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
            token = sb.toString();
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }

    static String pin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pin = prefs.getString(KEY_PIN, null);
        if (pin == null) {
            pin = String.format("%04d", new SecureRandom().nextInt(10000));
            prefs.edit().putString(KEY_PIN, pin).apply();
        }
        return pin;
    }

    static String localIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        String name = ni.getName().toLowerCase();
                        if (name.startsWith("eth")) return ip;
                        if (name.startsWith("wlan")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static String pairingUrl(Context context) {
        String ip = localIp();
        if (ip == null) return null;
        return "http://" + ip + ":" + PORT + "/?k=" + token(context);
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String header = readHeader(in);
            if (header == null) {
                socket.close();
                return;
            }
            String firstLine = header.split("\r\n")[0];
            String[] parts = firstLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            String target = parts[1];
            String path = target.split("\\?")[0];

            if ("websocket".equalsIgnoreCase(headerValue(header, "Upgrade"))) {
                String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
                if (!token(appContext).equals(queryParam(query, "token"))) {
                    writeHttp(out, 403, "text/plain", "bad token".getBytes(StandardCharsets.UTF_8));
                    socket.close();
                    return;
                }
                String key = headerValue(header, "Sec-WebSocket-Key");
                String accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.setSoTimeout(0);
                webSocketLoop(in, out);
                socket.close();
                return;
            }

            switch (path) {
                case "/":
                case "/index.html":
                    writeHttp(out, 200, "text/html; charset=utf-8",
                            RemotePage.HTML.getBytes(StandardCharsets.UTF_8));
                    break;
                case "/auth": {
                    String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
                    if (pin(appContext).equals(queryParam(query, "pin"))) {
                        String json = "{\"token\":\"" + token(appContext) + "\"}";
                        writeHttp(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
                    } else {
                        writeHttp(out, 403, "text/plain", "bad pin".getBytes(StandardCharsets.UTF_8));
                    }
                    break;
                }
                case "/status": {
                    String json = "{\"a11y\":" + TvAccessibilityService.isRunning()
                            + ",\"screen\":" + screenOn() + "}";
                    writeHttp(out, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/favicon.ico":
                    writeHttp(out, 204, "text/plain", new byte[0]);
                    break;
                default:
                    writeHttp(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
            socket.close();
        } catch (Exception ignored) {
            try {
                socket.close();
            } catch (Exception ignored2) {
            }
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while (buf.size() < 32768 && (b = in.read()) != -1) {
            buf.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') return buf.toString("UTF-8");
            else state = 0;
        }
        return buf.size() > 0 ? buf.toString("UTF-8") : null;
    }

    private static String headerValue(String header, String name) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    private static void writeHttp(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // ---- WebSocket ---------------------------------------------------------------------------------

    private static void webSocketLoop(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream fragmented = null;
        while (true) {
            int b0 = in.read();
            if (b0 == -1) return;
            int b1 = in.read();
            if (b1 == -1) return;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            byte[] mask = new byte[4];
            if (masked) readFully(in, mask);
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            }
            if (opcode == 8) {
                sendFrame(out, 8, new byte[0]);
                return;
            } else if (opcode == 9) {
                sendFrame(out, 10, payload);
            } else if (opcode == 1 || opcode == 0) {
                if (!fin) {
                    if (fragmented == null) fragmented = new ByteArrayOutputStream();
                    fragmented.write(payload);
                    continue;
                }
                byte[] full = payload;
                if (fragmented != null) {
                    fragmented.write(payload);
                    full = fragmented.toByteArray();
                    fragmented = null;
                }
                onMessage(new String(full, StandardCharsets.UTF_8), out);
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("eof");
            off += n;
        }
    }

    private static synchronized void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65536) {
            frame.write(126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) frame.write((int) (payload.length >>> (8 * i)));
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void sendStatus(OutputStream out) {
        try {
            String json = "{\"t\":\"status\",\"a11y\":" + TvAccessibilityService.isRunning()
                    + ",\"screen\":" + screenOn() + "}";
            sendFrame(out, 1, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** The power key is a toggle: screen on → sleep; screen off but process alive → light it up. */
    private static void togglePower() {
        if (screenOn()) {
            TvAccessibilityService svc = TvAccessibilityService.get();
            if (svc != null) svc.sleep();
        } else {
            try {
                Intent i = new Intent(appContext, WakeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean screenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    appContext.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception ignored) {
            return true;
        }
    }

    // ---- commands -----------------------------------------------------------------------------------

    private static void onMessage(String message, OutputStream out) {
        try {
            JSONObject msg = new JSONObject(message);
            String type = msg.optString("t");
            TvAccessibilityService svc = TvAccessibilityService.get();
            switch (type) {
                case "move":
                    if (svc != null) svc.movePointer((float) msg.optDouble("dx"), (float) msg.optDouble("dy"));
                    else sendStatus(out);
                    break;
                case "scroll":
                    if (svc != null) svc.scrollPointer((float) msg.optDouble("dx"), (float) msg.optDouble("dy"));
                    else sendStatus(out);
                    break;
                case "tap":
                    if (svc != null) svc.tapPointer();
                    else sendStatus(out);
                    break;
                case "key":
                    onKey(msg.optString("k"), svc, out);
                    break;
                case "text":
                    if (svc != null) svc.setText(msg.optString("s"));
                    else sendStatus(out);
                    break;
                case "gettext":
                    if (svc != null) {
                        String current = svc.focusedText();
                        String json = "{\"t\":\"textval\",\"s\":"
                                + JSONObject.quote(current == null ? "" : current) + "}";
                        sendFrame(out, 1, json.getBytes(StandardCharsets.UTF_8));
                    } else {
                        sendStatus(out);
                    }
                    break;
                case "status":
                    sendStatus(out);
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private static void onKey(String key, TvAccessibilityService svc, OutputStream out) {
        if ("power".equals(key)) {
            togglePower();
            return;
        }
        switch (key) {
            case "volup":
                adjustVolume(AudioManager.ADJUST_RAISE);
                return;
            case "voldn":
                adjustVolume(AudioManager.ADJUST_LOWER);
                return;
            case "mute":
                adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE);
                return;
            default:
                break;
        }
        if (svc == null) {
            sendStatus(out);
            return;
        }
        switch (key) {
            case "back":
                svc.pressBack();
                break;
            case "home":
                svc.pressHome();
                break;
            case "up":
                svc.pressDpad(View.FOCUS_UP);
                break;
            case "down":
                svc.pressDpad(View.FOCUS_DOWN);
                break;
            case "left":
                svc.pressDpad(View.FOCUS_LEFT);
                break;
            case "right":
                svc.pressDpad(View.FOCUS_RIGHT);
                break;
            case "ok":
            case "enter":
                svc.pressOk();
                break;
            default:
                break;
        }
    }

    private static void adjustVolume(int direction) {
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        } catch (Exception ignored) {
        }
    }
}
