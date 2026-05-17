package com.rccar.controller;

import org.json.JSONObject;

/**
 * ESP8266 Receiver'dan gelen telemetri paketi.
 *
 * JSON format:
 *   {"seq":N,"t":T,"s":S,"v":V,"cells":C,"cv":CV,"lv":LV,
 *    "gg":GG,"gd":GD,"gc":GC,"gr":GR}
 *
 *   seq   : paket sayacı (0-255)
 *   t     : throttle (-100..+100)
 *   s     : nihai steer (gyro dahil)
 *   v     : toplam pil voltajı (V)
 *   cells : tespit edilen hücre sayısı (0/2/3)
 *   cv    : hücre başına voltaj (V)
 *   lv    : düşük voltaj kilidi — 0=normal, 1=kilitli
 *   gg    : gyro gain (0-100)
 *   gd    : gyro direction (+1/-1)
 *   gc    : gyro düzeltme miktarı (-100..+100)
 *   gr    : ham gyro açısal hızı (°/s)
 */
public class TelemetryData {

    // LiPo voltaj sınırları (Android UI hesapları için)
    private static final float CELL_MIN_V  = 3.4f;
    private static final float CELL_MAX_V  = 4.2f;

    public final int   seq;
    public final int   throttle;
    public final int   steer;
    public final float voltage;
    // Pil
    public final int   cells;          // 0=bilinmiyor, 2=2S, 3=3S
    public final float cellVoltage;    // V/hücre
    public final boolean lowVoltage;   // true = motor kilitli
    // Gyro
    public final int   gyroGain;
    public final int   gyroDirection;
    public final int   gyroCorrection;
    public final float gyroRawRate;

    public final long receivedMs;

    public TelemetryData(int seq, int throttle, int steer, float voltage,
                         int cells, float cellVoltage, boolean lowVoltage,
                         int gyroGain, int gyroDirection,
                         int gyroCorrection, float gyroRawRate) {
        this.seq            = seq;
        this.throttle       = throttle;
        this.steer          = steer;
        this.voltage        = voltage;
        this.cells          = cells;
        this.cellVoltage    = cellVoltage;
        this.lowVoltage     = lowVoltage;
        this.gyroGain       = gyroGain;
        this.gyroDirection  = gyroDirection;
        this.gyroCorrection = gyroCorrection;
        this.gyroRawRate    = gyroRawRate;
        this.receivedMs     = System.currentTimeMillis();
    }

    /** JSON string'inden parse eder. Hata durumunda null döner. */
    public static TelemetryData parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new TelemetryData(
                o.optInt("seq", 0),
                o.optInt("t",   0),
                o.optInt("s",   0),
                (float) o.optDouble("v",  0.0),
                o.optInt("cells", 0),
                (float) o.optDouble("cv", 0.0),
                o.optInt("lv",  0) != 0,
                o.optInt("gg",  0),
                o.optInt("gd",  1),
                o.optInt("gc",  0),
                (float) o.optDouble("gr", 0.0)
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** Hücre voltajını 0.0–1.0 aralığında döner */
    public float cellLevelFraction() {
        if (cells == 0 || cellVoltage <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (cellVoltage - CELL_MIN_V) / (CELL_MAX_V - CELL_MIN_V)));
    }

    /** Pil çubuğu için renk kodu */
    public String batteryColor() {
        if (lowVoltage)              return "#F44336";  // kırmızı — kilitli
        float f = cellLevelFraction();
        if (f > 0.5f)               return "#4CAF50";  // yeşil
        if (f > 0.2f)               return "#FF9800";  // turuncu
        return                             "#F44336";  // kırmızı
    }

    /** Hücre sayısını metin olarak döner */
    public String cellsLabel() {
        return (cells == 0) ? "--S" : cells + "S";
    }

    public boolean isGyroActive() { return gyroGain > 0; }
}
