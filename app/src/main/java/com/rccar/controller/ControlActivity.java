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
 * Pil yönetimi UI:
 *   • Hücre sayısı (2S / 3S) telemetriden alınıp gösterilir
 *   • Hücre başına voltaj ayrı gösterilir
 *   • Düşük voltaj kilidi aktifken: turuncu uyarı banner + motor engeli bildirimi
 *   • Pil çubuğu rengini hücre voltajına göre günceller (yeşil→turuncu→kırmızı)
 */
public class ControlActivity extends AppCompatActivity {

    private static final String TAG = "ControlActivity";
    private static final int SEND_MS              = 50;
    private static final int TELEMETRY_TIMEOUT_MS = 3000;

    // --- Kontrol ---
    private SeekBar  sbThrottle, sbSteer, sbGyroGain;
    private TextView tvStatus;
    private TextView tvThrottle, tvSteer, tvTrim;
    private TextView tvGyroGain, tvGyroDir;
    private Button   btnTrimL, btnTrimR, btnReset, btnGyroDir, btnSetup;

    // --- Telemetri paneli ---
    private TextView    tvTelSeq, tvTelThrottle, tvTelSteer;
    private TextView    tvTelVoltage, tvTelCells, tvTelCellV;
    private TextView    tvTelGyroGain, tvTelGyroDir, tvTelGyroCorr, tvTelGyroRate;
    private ProgressBar pbVoltage;
    private TextView    tvLowVoltageWarning;  // Düşük voltaj uyarısı

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

    // 20Hz komut gönderici
    private final Runnable sender = new Runnable() {
        @Override public void run() {
            if (udp.isReady()) {
                udp.send(RCProtocol.buildPacketWithGyro(
                        throttle, steer + trim, gyroGain, gyroDir));
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
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
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
        tvTelCells    = findViewById(R.id.tvTelCells);
        tvTelCellV    = findViewById(R.id.tvTelCellV);
        tvTelGyroGain = findViewById(R.id.tvTelGyroGain);
        tvTelGyroDir  = findViewById(R.id.tvTelGyroDir);
        tvTelGyroCorr = findViewById(R.id.tvTelGyroCorr);
        tvTelGyroRate = findViewById(R.id.tvTelGyroRate);
        pbVoltage     = findViewById(R.id.pbVoltage);
        tvLowVoltageWarning = findViewById(R.id.tvLowVoltageWarning);

        refreshTrim();
        refreshGyroUI();
        resetTelemetryDisplay();
    }

    private void setupSeekBars() {
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

    private void setupButtons() {
        btnTrimL.setOnClickListener(v -> { trim = Math.max(-30, trim - 1); refreshTrim(); });
        btnTrimR.setOnClickListener(v -> { trim = Math.min( 30, trim + 1); refreshTrim(); });
        btnReset.setOnClickListener(v -> { trim = 0; refreshTrim(); });
        btnSetup.setOnClickListener(v -> goToSetup());
        btnGyroDir.setOnClickListener(v -> {
            gyroDir = (gyroDir > 0) ? -1 : 1;
            refreshGyroUI();
        });
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

    private void setupTelemetryListener() {
        udp.setTelemetryListener(data -> runOnUiThread(() -> updateTelemetryUI(data)));
    }

    private void updateTelemetryUI(TelemetryData data) {
        lastTelemetryMs   = System.currentTimeMillis();
        disconnectPending = false;

        // Temel telemetri
        tvTelSeq.setText("SEQ: " + data.seq);
        tvTelThrottle.setText("HIZ: " + data.throttle);
        tvTelSteer.setText("YON: " + data.steer);

        // Pil
        tvTelVoltage.setText(String.format("%.2fV", data.voltage));
        tvTelCells.setText(data.cellsLabel());
        tvTelCellV.setText(String.format("%.2fV/H", data.cellVoltage));

        try {
            tvTelVoltage.setTextColor(Color.parseColor(data.batteryColor()));
            tvTelCellV.setTextColor(Color.parseColor(data.batteryColor()));
        } catch (Exception ignored) {}

        // Pil çubuğu
        int pct = (int)(data.cellLevelFraction() * 100);
        pbVoltage.setProgress(pct);

        // Düşük voltaj uyarı banner'ı
        if (data.lowVoltage) {
            tvLowVoltageWarning.setVisibility(android.view.View.VISIBLE);
            tvLowVoltageWarning.setText(
                data.cells > 0
                ? String.format("⚠ DÜŞÜK PİL! %.2fV/hücre — MOTOR KİLİTLİ", data.cellVoltage)
                : "⚠ PİL ALGILANAMADI — MOTOR KİLİTLİ"
            );
        } else {
            tvLowVoltageWarning.setVisibility(android.view.View.GONE);
        }

        // Gyro
        tvTelGyroGain.setText("GG: " + data.gyroGain + "%");
        tvTelGyroDir.setText("GD: " + (data.gyroDirection > 0 ? "NRM" : "TRS"));
        tvTelGyroCorr.setText("GC: " + data.gyroCorrection);
        tvTelGyroRate.setText(String.format("GR: %.1f°/s", data.gyroRawRate));

        tvTelGyroGain.setTextColor(data.isGyroActive()
                ? Color.parseColor("#4CAF50")
                : Color.parseColor("#757575"));
    }

    private void resetTelemetryDisplay() {
        tvTelSeq.setText("SEQ: --");
        tvTelThrottle.setText("HIZ: --");
        tvTelSteer.setText("YON: --");
        tvTelVoltage.setText("--V");
        tvTelCells.setText("?S");
        tvTelCellV.setText("--V/H");
        tvTelGyroGain.setText("GG: --");
        tvTelGyroDir.setText("GD: --");
        tvTelGyroCorr.setText("GC: --");
        tvTelGyroRate.setText("GR: --");
        pbVoltage.setProgress(0);
        tvLowVoltageWarning.setVisibility(android.view.View.GONE);
    }

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
                    if (!disconnectPending) onConnectionLost();
                });
            }
            public void onError(String m) {
                runOnUiThread(() -> {
                    tvStatus.setText("Hata: " + m);
                    handler.postDelayed(this::goSetup, 1500);
                });
            }
            private void goSetup() { goToSetup(); }
        });
    }

    private void monitorNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@androidx.annotation.NonNull Network network) {
                onConnectionLost();
            }
        };
        try { cm.registerNetworkCallback(req, networkCallback); }
        catch (Exception e) { Log.e(TAG, "Network callback", e); }
    }

    private void onConnectionLost() {
        if (disconnectPending) return;
        disconnectPending = true;
        runOnUiThread(() -> {
            tvStatus.setText("⚠ Bağlantı koptu");
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
