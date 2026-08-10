package com.monitorwhatsapp.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recovers the monitoring stack after app updates or device restart, when access is still granted. */
public class MonitorRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        DiagnosticLog.append(context, "RECOVERY_BROADCAST", "action=" + action + " / " + ListenerHealth.snapshot(context));
        if (ListenerHealth.accessGranted(context)) {
            NotificationMonitorService.requestHardReconnect(context, "recovery:" + action);
            MonitorWatchdogService.ensureRunning(context);
        }
    }
}
