package com.monitorwhatsapp.app;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps short-lived references to the original WhatsApp notification actions. */
public final class NotificationActionRegistry {
    public static final int OPEN_FAILED = 0;
    public static final int OPEN_DIRECT = 1;
    public static final int OPEN_APP_FALLBACK = 2;

    private static final Map<String, PendingIntent> BY_OCCURRENCE = new ConcurrentHashMap<>();
    private static final Map<String, PendingIntent> BY_NOTIFICATION = new ConcurrentHashMap<>();
    private static final Map<String, PendingIntent> BY_GROUP = new ConcurrentHashMap<>();

    private NotificationActionRegistry() {}

    public static void register(Occurrence occurrence, PendingIntent action) {
        if (occurrence == null || action == null) return;
        if (occurrence.id != null && !occurrence.id.isEmpty()) BY_OCCURRENCE.put(occurrence.id, action);
        if (occurrence.notificationKey != null && !occurrence.notificationKey.isEmpty()) {
            BY_NOTIFICATION.put(occurrence.notificationKey, action);
        }
        String group = TextMatcher.normalize(occurrence.group);
        if (!group.isEmpty()) BY_GROUP.put(group, action);
        trimIfNeeded();
    }

    public static int open(Context context, Occurrence occurrence) {
        if (occurrence == null) return OPEN_FAILED;

        // First try the Android-system-owned wrapper. It is more durable than the in-memory cache.
        if (ConversationLink.open(context, occurrence)) return OPEN_DIRECT;

        PendingIntent cached = BY_OCCURRENCE.get(occurrence.id);
        if (send(context, cached)) return OPEN_DIRECT;

        cached = BY_NOTIFICATION.get(occurrence.notificationKey);
        if (send(context, cached)) return OPEN_DIRECT;

        String group = TextMatcher.normalize(occurrence.group);
        cached = group.isEmpty() ? null : BY_GROUP.get(group);
        if (send(context, cached)) return OPEN_DIRECT;

        if (NotificationMonitorService.openActiveNotification(occurrence.notificationKey)) {
            return OPEN_DIRECT;
        }

        String pkg = occurrence.sourcePackage == null || occurrence.sourcePackage.isEmpty()
                ? "com.whatsapp" : occurrence.sourcePackage;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null && !"com.whatsapp".equals(pkg)) {
            launch = context.getPackageManager().getLaunchIntentForPackage("com.whatsapp");
        }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return OPEN_APP_FALLBACK;
        }
        return OPEN_FAILED;
    }

    public static void clear() {
        BY_OCCURRENCE.clear();
        BY_NOTIFICATION.clear();
        BY_GROUP.clear();
    }

    static boolean send(PendingIntent action) {
        return send(null, action);
    }

    /**
     * Android 14+ requires the sender of a PendingIntent to opt in when that action may launch
     * another app's Activity. We only grant the option for PendingIntents created by WhatsApp.
     */
    static boolean send(Context context, PendingIntent action) {
        if (action == null) return false;
        try {
            String creator = action.getCreatorPackage();
            boolean trustedWhatsApp = "com.whatsapp".equals(creator) || "com.whatsapp.w4b".equals(creator);
            if (context != null && trustedWhatsApp && Build.VERSION.SDK_INT >= 34) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                action.send(context, 0, null, null, null, null, options.toBundle());
            } else {
                action.send();
            }
            return true;
        } catch (PendingIntent.CanceledException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void trimIfNeeded() {
        if (BY_OCCURRENCE.size() > 300 || BY_NOTIFICATION.size() > 300 || BY_GROUP.size() > 100) {
            clear();
        }
    }
}
