package com.stepx.amoobridge;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Remote wake-up entry point. The server must send a high-priority data message
 * with data.type=bridge_wake. FCM configuration is optional at build time but
 * google-services.json is required before a device can receive real messages.
 */
public class BridgeFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "BridgeFCM";

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String type = message.getData().get("type");
        if (!"bridge_wake".equals(type)) return;
        try {
            Intent i = new Intent(this, BridgeService.class);
            i.putExtra(BridgeService.EXTRA_WAKE_WINDOW, true);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to start bridge after FCM wake", t);
        }
    }

    @Override
    public void onNewToken(String token) {
        BridgeFcm.persistToken(token);
        BridgeFcm.writeStatus("ok", null, token);
        Log.i(TAG, "New FCM token: " + BridgeFcm.mask(token));
    }
}
