package com.stepx.amoobridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Safety net for {@link KeepHot}: the alarm is delivered by system_server, so
 * the broadcast itself is an inbound binder call that thaws a frozen uid. Once
 * running, the keeper thread takes over again.
 */
public class KeepaliveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            KeepHot.init(ctx);
            KeepHot.onSafetyAlarm();
        } catch (Throwable ignored) {
        }
    }
}
