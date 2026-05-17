package com.rccar.controller;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Objects;

/**
 * ESP8266 AP'sine programatik olarak bağlanır.
 * Android 10+ (API 29+): WifiNetworkSpecifier
 * Android 9 ve altı:     WifiManager legacy API
 */
public class WifiConnectHelper {

    public interface Callback {
        void onSuccess();
        void onFailed(String reason);
    }

    private static final String TAG = "RC_WiFi";

    private final Context ctx;
    private ConnectivityManager.NetworkCallback netCallback;

    public WifiConnectHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void connect(String ssid, String password, Callback cb) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, password, cb);
        } else {
            connectLegacy(ssid, password, cb);
        }
    }

    // Android 10+ ---------------------------------------------------------------
    @RequiresApi(Build.VERSION_CODES.Q)
    private void connectModern(String ssid, String password, Callback cb) {
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build();

        NetworkRequest req = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec)
            .build();

        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);

        // Önceki callback varsa kaldır
        if (netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // Bu ağ üzerinden tüm trafiği yönlendir (internet yokken de çalışır)
                cm.bindProcessToNetwork(network);
                Log.d(TAG, "WiFi bağlandı (modern): " + ssid);
                if (cb != null) cb.onSuccess();
            }
            @Override
            public void onUnavailable() {
                Log.w(TAG, "WiFi unavailable: " + ssid);
                if (cb != null) cb.onFailed("Ağa bağlanılamadı: " + ssid);
            }
        };

        cm.requestNetwork(req, netCallback);
    }

    // Android 9 ve altı ---------------------------------------------------------
    @SuppressWarnings({"deprecation", "MissingPermission"})
    private void connectLegacy(String ssid, String password, Callback cb) {
        WifiManager wm = (WifiManager)
            ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);

        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID            = "\"" + ssid + "\"";
        conf.preSharedKey    = "\"" + password + "\"";

        int netId = wm.addNetwork(conf);
        if (netId == -1) {
            // Daha önce eklenmiş olabilir, bul
            for (WifiConfiguration c : wm.getConfiguredNetworks()) {
                if (Objects.equals(c.SSID, "\"" + ssid + "\"")) { netId = c.networkId; break; }
            }
        }
        if (netId == -1) { if (cb!=null) cb.onFailed("Ağ eklenemedi"); return; }

        wm.disconnect();
        wm.enableNetwork(netId, true);
        wm.reconnect();

        Log.d(TAG, "WiFi bağlantısı istendi (legacy): " + ssid);
        // Legacy API senkron değil; kısa gecikme sonrası success sayıyoruz
        final int fNetId = netId;
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(() -> {
                if (wm.getConnectionInfo().getNetworkId() == fNetId) {
                    if (cb != null) cb.onSuccess();
                } else {
                    if (cb != null) cb.onFailed("Bağlantı zaman aşımı");
                }
            }, 3000);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    public void release() {
        if (netCallback != null) {
            ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
    }
}
