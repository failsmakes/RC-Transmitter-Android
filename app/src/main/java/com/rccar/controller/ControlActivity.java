package com.rccar.controller;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Ana kontrol ekranı.
 *
 * Yeni özellikler:
 *   • Gyro Gain SeekBar  (0–100, anlık gönderim)
 *   • Gyro Direction Toggle butonu (+1 / -1)
 *   • Telemetri paneline gyro bilgileri eklendi (gain, dir, correction, raw rate)
 *
 * Her komut paketine gyroGain + gyroDir eklenerek receiver'a gönderilir.
 * Receiver, gelen GG/GD değerlerine göre gyro işlemcisini günceller.
 */
public class ControlActivity extends AppCompatActivity {

    private static final String TAG = "ControlActivity";
    private static final int SEND_MS              = 50;    // 20 Hz
    private static final int TELEMETRY_TIMEOUT_MS = 3000;

    // --- Kontrol arayüzü ---
    private SeekBar  sbThrottle, sbSteer;
    private SeekBar  sbGyroGain;
    private TextView tvStatus;
    private TextView tvThrottle, tvSteer, tvTrim;
    private TextView tvGyroGain, tvGyroDir;
    private Button   btnTrimL, btnTrimR, btnReset;
    private Button   btnGyroDir, btnSetup;

    // --- Telemetri paneli ---
    private TextView    tvTelSeq, tvTelThrottle, tvTelSteer, tvTelVoltage;
    private TextView    tvTelGyroGain, tvTelGyroDir, tvTelGyroCorr, tvTelGyroRate;
    private ProgressBar pbVoltage;

    private ConnectionConfig config;
    private UdpManager       udp;

    private int  throttle   = 0;
    private int  steer      = 0;
    private int  trim       = 0;
    private int  gyroGain   = 50;
    private int  gyroDir    = 1;

    private long    lastTelemetryMs   = 0;
    private long    startTimeMs       = 0;
    private boolean disconnectPending = false;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 20Hz komut gönderici — her pakete gyro parametreleri eklenir
    private final Runnable sender = new Runnable() {
        @Override public void run() {
            if (udp.isReady()) {
                byte[] pkt = RCProtocol.buildPacketWithGyro(
                        throttle, steer + trim, gyroGain, gyroDir);
                udp.send(pkt);
            }
            handler.postDelayed(this, SEND_MS);
        }
    };

    private final Runnable telemetryWatchdog = new Runnable() {
        @Override public void run() {
            long now  = System.currentTimeMillis();
            long last = (lastTelemetryMs > 0) ? lastTelemetryMs : startTimeMs;
            if (now - last > TELEMETRY_TIMEOUT_MS && !disconnectPending) {
                disconnectPending = true;
                onConnectionLost();
            }
            checkSsidChanged();
            if (!disconnectPending) handler.postDelayed(this, 1000);
        }
    };

    private void checkSsidChanged() {
        if (disconnectPending) return;
        String targetSsid = config.getSsid();
        if (targetSsid == null || targetSsid.isEmpty()) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            if (ssid != null) {
                if (ssid.startsWith("\"") && ssid.endsWith("\""))
                    ssid = ssid.substring(1, ssid.length() - 1);
                if (!ssid.equals("<unknown ssid>") && !ssid.equals(targetSsid)) {
                    Log.w(TAG, "SSID değişti: " + ssid);
                    onConnectionLost();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_control);

        config   = new ConnectionConfig(this);
        trim     = config.getTrim();
        gyroGain = config.getGyroGain();
        gyroDir  = config.getGyroDirection();
        udp      = new UdpManager();

        bindViews();
        setupSeekBars();
        setupButtons();
        setupTelemetryListener();
        connectUdp();
    }

    // -------------------------------------------------------------------------
    private void bindViews() {
        sbThrottle    = findViewById(R.id.sbThrottle);
        sbSteer       = findViewById(R.id.sbSteer);
        sbGyroGain    = findViewById(R.id.sbGyroGain);
        tvStatus      = findViewById(R.id.tvStatus);
        tvThrottle    = findViewById(R.id.tvThrottle);
        tvSteer       = findViewById(R.id.tvSteer);
        tvTrim        = findViewById(R.id.tvTrim);
        tvGyroGain    = findViewById(R.id.tvGyroGain);
        tvGyroDir     = findViewById(R.id.tvGyroDir);
        btnTrimL      = findViewById(R.id.btnTrimL);
        btnTrimR      = findViewById(R.id.btnTrimR);
        btnReset      = findViewById(R.id.btnReset);
        btnGyroDir    = findViewById(R.id.btnGyroDir);
        btnSetup      = findViewById(R.id.btnSetup);

        tvTelSeq      = findViewById(R.id.tvTelSeq);
        tvTelThrottle = findViewById(R.id.tvTelThrottle);
        tvTelSteer    = findViewById(R.id.tvTelSteer);
        tvTelVoltage  = findViewById(R.id.tvTelVoltage);
        tvTelGyroGain = findViewById(R.id.tvTelGyroGain);
        tvTelGyroDir  = findViewById(R.id.tvTelGyroDir);
        tvTelGyroCorr = findViewById(R.id.tvTelGyroCorr);
        tvTelGyroRate = findViewById(R.id.tvTelGyroRate);
        pbVoltage     = findViewById(R.id.pbVoltage);

        refreshTrim();
        refreshGyroUI();
        resetTelemetryDisplay();
    }

    // -------------------------------------------------------------------------
    private void setupSeekBars() {
        // Throttle
        sbThrottle.setMax(200);
        sbThrottle.setProgress(100);
        sbThrottle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                throttle = p - 100;
                tvThrottle.setText("GAZ: " + throttle);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                sb.setProgress(100);
                throttle = 0;
                tvThrottle.setText("GAZ: 0");
            }
        });

        // Steer
        sbSteer.setMax(200);
        sbSteer.setProgress(100);
        sbSteer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                steer = p - 100;
                tvSteer.setText("YÖN: " + steer);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                sb.setProgress(100);
                steer = 0;
                tvSteer.setText("YÖN: 0");
            }
        });

        // Gyro Gain
        sbGyroGain.setMax(100);
        sbGyroGain.setProgress(gyroGain);
        sbGyroGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                gyroGain = p;
                refreshGyroUI();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // -------------------------------------------------------------------------
    private void setupButtons() {
        btnTrimL.setOnClickListener(v -> { trim = Math.max(-30, trim - 1); refreshTrim(); });
        btnTrimR.setOnClickListener(v -> { trim = Math.min( 30, trim + 1); refreshTrim(); });
        btnReset.setOnClickListener(v -> { trim = 0; refreshTrim(); });
        btnSetup.setOnClickListener(v -> goToSetup());
        btnGyroDir.setOnClickListener(v -> toggleGyroDirection());
    }

    private void toggleGyroDirection() {
        gyroDir = (gyroDir > 0) ? -1 : 1;
        refreshGyroUI();
    }

    private void refreshTrim() {
        tvTrim.setText("TRIM: " + (trim >= 0 ? "+" : "") + trim + "°");
    }

    private void refreshGyroUI() {
        if (gyroGain == 0) {
            tvGyroGain.setText("GYRO: KAPALI");
            tvGyroGain.setTextColor(Color.parseColor("#757575"));
        } else {
            tvGyroGain.setText("GYRO: " + gyroGain + "%");
            tvGyroGain.setTextColor(Color.parseColor("#4CAF50"));
        }
        tvGyroDir.setText("YÖN: " + (gyroDir > 0 ? "NRM" : "TRS"));
        btnGyroDir.setText(gyroDir > 0 ? "▶ NRM" : "◀ TRS");
    }

    // -------------------------------------------------------------------------
    private void setupTelemetryListener() {
        udp.setTelemetryListener(data -> runOnUiThread(() -> updateTelemetryUI(data)));
    }

    private void updateTelemetryUI(TelemetryData data) {
        lastTelemetryMs   = System.currentTimeMillis();
        disconnectPending = false;

        tvTelSeq.setText("SEQ: " + data.seq);
        tvTelThrottle.setText("HIZ: " + data.throttle);
        tvTelSteer.setText("YON: " + data.steer);
        tvTelVoltage.setText(String.format("BAT: %.2fV", data.voltage));

        // Gyro telemetri
        tvTelGyroGain.setText("GG: " + data.gyroGain + "%");
        tvTelGyroDir.setText("GD: " + (data.gyroDirection > 0 ? "NRM" : "TRS"));
        tvTelGyroCorr.setText("GC: " + data.gyroCorrection);
        tvTelGyroRate.setText(String.format("GR: %.1f°/s", data.gyroRawRate));

        // Gyro renk göstergesi
        if (data.isGyroActive()) {
            tvTelGyroGain.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            tvTelGyroGain.setTextColor(Color.parseColor("#757575"));
        }

        int pct = (int)(data.voltagePercent() * 100);
        pbVoltage.setProgress(pct);
        try { tvTelVoltage.setTextColor(Color.parseColor(data.voltageColor())); }
        catch (Exception ignored) {}
    }

    private void resetTelemetryDisplay() {
        tvTelSeq.setText("SEQ: --");
        tvTelThrottle.setText("HIZ: --");
        tvTelSteer.setText("YON: --");
        tvTelVoltage.setText("BAT: -.-V");
        tvTelGyroGain.setText("GG: --");
        tvTelGyroDir.setText("GD: --");
        tvTelGyroCorr.setText("GC: --");
        tvTelGyroRate.setText("GR: --");
        pbVoltage.setProgress(0);
    }

    // -------------------------------------------------------------------------
    private void connectUdp() {
        tvStatus.setText("Bağlanılıyor...");
        startTimeMs = System.currentTimeMillis();
        udp.connect(config.getIp(), config.getPort(), new UdpManager.Listener() {
            public void onConnected() {
                runOnUiThread(() -> {
                    startTimeMs = System.currentTimeMillis();
                    tvStatus.setText("Bağlı ✓  " + config.getIp());
                    handler.postDelayed(sender, SEND_MS);
                    handler.postDelayed(telemetryWatchdog, 500);
                    monitorNetwork();
                });
            }
            public void onDisconnected() {
                runOnUiThread(() -> {
                    tvStatus.setText("Bağlantı kesildi");
                    if (!disconnectPending) onConnectionLost();
                });
            }
            public void onError(String m) {
                runOnUiThread(() -> {
                    tvStatus.setText("Hata: " + m);
                    handler.postDelayed(this::goToSetupDelayed, 1500);
                });
            }
            private void goToSetupDelayed() { goToSetup(); }
        });
    }

    private void monitorNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@androidx.annotation.NonNull Network network) {
                Log.w(TAG, "WiFi ağı kaybedildi");
                onConnectionLost();
            }
        };
        try { cm.registerNetworkCallback(request, networkCallback); }
        catch (Exception e) { Log.e(TAG, "Network callback hatası", e); }
    }

    private void onConnectionLost() {
        if (disconnectPending) return;
        disconnectPending = true;
        runOnUiThread(() -> {
            tvStatus.setText("⚠ Bağlantı koptu — yeniden bağlanılıyor...");
            tvStatus.setTextColor(Color.RED);
            resetTelemetryDisplay();
            handler.postDelayed(this::goToSetup, 2000);
        });
    }

    private void goToSetup() {
        disconnectPending = true;
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
        config.setTrim(trim);
        config.setGyroGain(gyroGain);
        config.setGyroDirection(gyroDir);
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.send(RCProtocol.buildPacketWithGyro(0, trim, gyroGain, gyroDir));
        udp.disconnect();
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    // -------------------------------------------------------------------------
    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.send(RCProtocol.buildPacketWithGyro(0, trim, gyroGain, gyroDir));
    }

    @Override protected void onResume() {
        super.onResume();
        if (udp.isReady()) {
            handler.postDelayed(sender, SEND_MS);
            handler.postDelayed(telemetryWatchdog, 500);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        config.setTrim(trim);
        config.setGyroGain(gyroGain);
        config.setGyroDirection(gyroDir);
        handler.removeCallbacks(sender);
        handler.removeCallbacks(telemetryWatchdog);
        udp.disconnect();
    }
}
