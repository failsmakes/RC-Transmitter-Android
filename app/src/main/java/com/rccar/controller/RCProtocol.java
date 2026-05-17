package com.rccar.controller;

import java.util.Locale;

/**
 * ESP8266 Receiver'ın beklediği UDP paket formatını üretir.
 *
 * Standart format: {"G":throttle,"Y":steer}
 * Gyro parametreli: {"G":throttle,"Y":steer,"GG":gain,"GD":direction}
 *
 * Receiver hem GG hem GD içeren paketi gyro ayar güncelleme olarak yorumlar.
 * GG / GD gönderilmediğinde receiver mevcut değerleri korur.
 */
public class RCProtocol {

    /**
     * Gyro parametreli kontrol paketi.
     *
     * @param throttle  -100 … +100
     * @param steer     -100 … +100
     * @param gyroGain  0 … 100  (0 = gyro kapalı)
     * @param gyroDir   +1 veya -1
     */
    public static byte[] buildPacketWithGyro(int throttle, int steer, int gyroGain, int gyroDir) {
        String msg = String.format(Locale.US,
            "{\"G\":%d,\"Y\":%d,\"GG\":%d,\"GD\":%d}",
            throttle, steer, gyroGain, gyroDir);
        return msg.getBytes();
    }
}
