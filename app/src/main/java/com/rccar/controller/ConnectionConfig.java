package com.rccar.controller;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Bağlantı ve uygulama ayarlarını SharedPreferences'ta saklar.
 * Gyro gain ve direction değerleri de burada tutulur.
 */
public class ConnectionConfig {
    private static final String PREFS_NAME = "RC_CONFIG";
    private final SharedPreferences prefs;

    public ConnectionConfig(Context context) {
        this.prefs = context.getApplicationContext()
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Bağlantı ---
    public String getSsid() { return prefs.getString("ssid", "ESP_RC_CAR"); }
    public void setSsid(String s) { prefs.edit().putString("ssid", s).apply(); }

    public String getPass() { return prefs.getString("pass", "12345678"); }
    public void setPass(String s) { prefs.edit().putString("pass", s).apply(); }

    public String getIp() { return prefs.getString("ip", "192.168.4.1"); }
    public void setIp(String s) { prefs.edit().putString("ip", s).apply(); }

    public int getPort() { return prefs.getInt("port", 4210); }
    public void setPort(int p) { prefs.edit().putInt("port", p).apply(); }

    // --- Trim ---
    public int getTrim() { return prefs.getInt("trim", 0); }
    public void setTrim(int t) { prefs.edit().putInt("trim", t).apply(); }

    // --- Gyro ---
    /** Gyro gain: 0 (kapalı) – 100 (maksimum) */
    public int getGyroGain() { return prefs.getInt("gyro_gain", 50); }
    public void setGyroGain(int g) { prefs.edit().putInt("gyro_gain", g).apply(); }

    /** Gyro direction: +1 (normal) veya -1 (ters) */
    public int getGyroDirection() { return prefs.getInt("gyro_dir", 1); }
    public void setGyroDirection(int d) { prefs.edit().putInt("gyro_dir", d).apply(); }
}
