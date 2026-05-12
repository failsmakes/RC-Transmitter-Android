package com.rccar.controller;

import org.json.JSONObject;

/**
 * ESP8266 Receiver'dan gelen telemetri paketi.
 *
 * JSON format:
 *   {"seq":N,"t":T,"s":S,"v":V,"gg":GG,"gd":GD,"gc":GC,"gr":GR}
 *
 *   seq : paket sayacı (0-255)
 *   t   : throttle değeri (-100..+100)
 *   s   : nihai steer (gyro düzeltmesi uygulanmış, -100..+100)
 *   v   : pil voltajı (float, V)
 *   gg  : aktif gyro gain (0-100)
 *   gd  : aktif gyro direction (+1 / -1)
 *   gc  : gyro düzeltme miktarı (-100..+100)
 *   gr  : ham gyro açısal hızı (°/s)
 */
public class TelemetryData {
    public final int   seq;
    public final int   throttle;
    public final int   steer;
    public final float voltage;
    // Gyro alanları
    public final int   gyroGain;       // 0-100
    public final int   gyroDirection;  // +1 veya -1
    public final int   gyroCorrection; // -100..+100
    public final float gyroRawRate;    // °/s
    public final long  receivedMs;

    public TelemetryData(int seq, int throttle, int steer, float voltage,
                         int gyroGain, int gyroDirection, int gyroCorrection, float gyroRawRate) {
        this.seq            = seq;
        this.throttle       = throttle;
        this.steer          = steer;
        this.voltage        = voltage;
        this.gyroGain       = gyroGain;
        this.gyroDirection  = gyroDirection;
        this.gyroCorrection = gyroCorrection;
        this.gyroRawRate    = gyroRawRate;
        this.receivedMs     = System.currentTimeMillis();
    }

    /** JSON string'ini parse eder. Hata durumunda null döner. */
    public static TelemetryData parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int   seq       = o.optInt("seq", 0);
            int   throttle  = o.optInt("t",   0);
            int   steer     = o.optInt("s",   0);
            float voltage   = (float) o.optDouble("v",  0.0);
            int   gg        = o.optInt("gg",  0);
            int   gd        = o.optInt("gd",  1);
            int   gc        = o.optInt("gc",  0);
            float gr        = (float) o.optDouble("gr", 0.0);
            return new TelemetryData(seq, throttle, steer, voltage, gg, gd, gc, gr);
        } catch (Exception e) {
            return null;
        }
    }

    /** Voltaj seviyesini 0.0-1.0 arasında döner (2S LiPo: 6.0V-8.4V) */
    public float voltagePercent() {
        final float MIN_V = 6.0f;
        final float MAX_V = 8.4f;
        return Math.max(0f, Math.min(1f, (voltage - MIN_V) / (MAX_V - MIN_V)));
    }

    /** Voltaja göre renk kodu */
    public String voltageColor() {
        float pct = voltagePercent();
        if (pct > 0.5f) return "#4CAF50";
        if (pct > 0.2f) return "#FF9800";
        return "#F44336";
    }

    /** Gyro aktif mi? */
    public boolean isGyroActive() {
        return gyroGain > 0;
    }
}
