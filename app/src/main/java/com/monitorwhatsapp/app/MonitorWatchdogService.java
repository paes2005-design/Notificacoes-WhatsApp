package com.monitorwhatsapp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class MonitorWatchdogService extends Service {
    private static final String CHANNEL_ID = "monitor_watchdog_v1";
    private static final int NOTIFICATION_ID = 4105;
    private static final long CHECK_MS = 15_000L;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastHeartbeatAt;

    private final Runnable checker = new Runnable() {
        @Override public void run() {
            try {
                boolean access = ListenerHealth.accessGranted(MonitorWatchdogService.this);
                boolean connected = NotificationMonitorService.isConnectedNow();
                if (access && !connected) {
                    NotificationMonitorService.requestHardReconnect(MonitorWatchdogService.this, "watchdog");
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeatAt > 120_000L) {
                    lastHeartbeatAt = now;
                    DiagnosticLog.append(MonitorWatchdogService.this, "WATCHDOG_HEARTBEAT",
                            "access=" + access + " connected=" + connected);
                }
            } catch (Throwable t) {
                DiagnosticLog.error(MonitorWatchdogService.this, "watchdogTick", t);
            } finally {
                handler.postDelayed(this, CHECK_MS);
            }
        }
    };

    public static boolean isRunning() { return running; }

    public static void ensureRunning(Context context) {
        if (context == null || !ListenerHealth.accessGranted(context)) return;
        try {
            Intent i = new Intent(context, MonitorWatchdogService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
            DiagnosticLog.append(context, "WATCHDOG_START_REQUESTED", ListenerHealth.snapshot(context));
        } catch (Throwable t) {
            DiagnosticLog.error(context, "startWatchdog", t);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startAsForeground();
        DiagnosticLog.append(this, "WATCHDOG_CREATED", ListenerHealth.snapshot(this));
        handler.post(checker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        DiagnosticLog.append(this, "WATCHDOG_DESTROYED", ListenerHealth.snapshot(this));
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Monitor ativo", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Mantém o monitor de assuntos disponível em segundo plano.");
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ClientMainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 4105, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(com.monitorwhatsapp.app.R.drawable.ic_app)
                .setContentTitle("Alerta de Assuntos ativo")
                .setContentText("Monitorando notificações do WhatsApp em segundo plano")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
