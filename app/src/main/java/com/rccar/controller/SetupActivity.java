package com.rccar.controller;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * ESP8266 AP bağlantı ayarları.
 * SSID / Şifre / IP / Port yapılandırması.
 */
public class SetupActivity extends AppCompatActivity {

    private EditText etSsid, etPass, etIp, etPort;
    private Button   btnConnect;
    private TextView tvStatus;

    private ConnectionConfig    config;
    private WifiConnectHelper   wifiHelper;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_setup);

        config     = new ConnectionConfig(this);
        wifiHelper = new WifiConnectHelper(this);

        etSsid  = findViewById(R.id.etSsid);
        etPass  = findViewById(R.id.etPass);
        etIp    = findViewById(R.id.etIp);
        etPort  = findViewById(R.id.etPort);
        btnConnect = findViewById(R.id.btnConnect);
        tvStatus   = findViewById(R.id.tvStatus);

        // Kayıtlı değerleri yükle
        etSsid.setText(config.getSsid());
        etPass.setText(config.getPass());
        etIp.setText(config.getIp());
        etPort.setText(String.format(Locale.US, "%d", config.getPort()));

        btnConnect.setOnClickListener(v -> startConnect());
    }

    private void startConnect() {
        String ssid = etSsid.getText().toString().trim();
        String pass = etPass.getText().toString().trim();
        String ip   = etIp.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();

        if (ssid.isEmpty() || pass.isEmpty() || ip.isEmpty()) {
            tvStatus.setText(String.format(Locale.US, "%s", "Tüm alanları doldurun"));
            return;
        }

        int portVal;
        try {
            portVal = Integer.parseInt(portStr);
        } catch (Exception ignored) {
            portVal = 4210;
        }
        final int port = portVal;

        // Kaydet
        config.setSsid(ssid);
        config.setPass(pass);
        config.setIp(ip);
        config.setPort(port);

        btnConnect.setEnabled(false);
        tvStatus.setText(String.format(Locale.US, "%s", "WiFi'ya bağlanılıyor..."));

        wifiHelper.connect(ssid, pass, new WifiConnectHelper.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    tvStatus.setText(String.format(Locale.US, "%s", "Bağlandı ✓"));
                    // Kısa gecikme ile kontrol ekranına geç
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> {
                            startActivity(new Intent(SetupActivity.this, ControlActivity.class));
                            finish();
                        }, 600);
                });
            }
            @Override
            public void onFailed(String reason) {
                runOnUiThread(() -> {
                    tvStatus.setText(String.format(Locale.US, "Hata: %s", reason));
                    btnConnect.setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // wifiHelper.release(); // AP bağlantısını kesmek istemiyorsak çağırma
    }
}
