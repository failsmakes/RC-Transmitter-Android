package com.rccar.controller;

import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDP gönderici + alıcı.
 * - Komut gönderme: ESP8266 port 4210
 * - Telemetri alma: yerel port 4211 (ESP8266'dan gelen JSON)
 */
public class UdpManager {

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onError(String msg);
    }

    public interface TelemetryListener {
        void onTelemetry(TelemetryData data);
    }

    private static final String TAG      = "RC_UDP";
    private static final int    RECV_BUF = 256;

    private DatagramSocket   sendSocket;
    private DatagramSocket   recvSocket;
    private InetAddress      address;
    private int              port;
    private volatile boolean ready       = false;
    private volatile boolean recvRunning = false;

    private final ExecutorService sendExec = Executors.newSingleThreadExecutor();
    private final ExecutorService recvExec = Executors.newSingleThreadExecutor();

    private Listener          listener;
    private TelemetryListener telemetryListener;

    // -------------------------------------------------------------------------
    public void connect(String ip, int p, Listener l) {
        this.listener = l;
        sendExec.execute(() -> {
            try {
                address    = InetAddress.getByName(ip);
                port       = p;
                sendSocket = new DatagramSocket();
                sendSocket.setBroadcast(false);
                sendSocket.setSoTimeout(0);
                ready = true;
                if (listener != null) listener.onConnected();
                startReceiving(p + 1);   // telemetri port = komut port + 1 = 4211
            } catch (Exception e) {
                Log.e(TAG, "connect", e);
                if (listener != null) listener.onError(e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    private void startReceiving(int recvPort) {
        recvExec.execute(() -> {
            try {
                if (recvSocket != null) recvSocket.close();
                recvSocket  = new DatagramSocket(recvPort);
                recvSocket.setSoTimeout(2000);   // 2s timeout — bağlantı kontrolü için
                recvRunning = true;
                byte[] buf  = new byte[RECV_BUF];

                while (recvRunning && !recvSocket.isClosed()) {
                    try {
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        recvSocket.receive(dp);
                        String json = new String(buf, 0, dp.getLength(), StandardCharsets.UTF_8);
                        if (telemetryListener != null) {
                            TelemetryData td = TelemetryData.parse(json);
                            if (td != null) telemetryListener.onTelemetry(td);
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                        // timeout: döngüye devam et
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "recv", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    public void send(byte[] data) {
        if (!ready || data == null) return;
        sendExec.execute(() -> {
            try {
                DatagramPacket dp = new DatagramPacket(data, data.length, address, port);
                sendSocket.send(dp);
            } catch (Exception e) {
                Log.e(TAG, "send", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    public void disconnect() {
        ready       = false;
        recvRunning = false;
        sendExec.execute(() -> {
            if (sendSocket != null) { sendSocket.close(); sendSocket = null; }
            if (recvSocket != null) { recvSocket.close(); recvSocket = null; }
            if (listener != null) listener.onDisconnected();
        });
    }

    public void setTelemetryListener(TelemetryListener l) { this.telemetryListener = l; }
    public boolean isReady() { return ready; }
}
