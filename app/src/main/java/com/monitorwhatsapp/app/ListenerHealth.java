package com.monitorwhatsapp.app;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

public final class ListenerHealth {
    private ListenerHealth() {}

    public static ComponentName component(Context context) {
        return new ComponentName(context, NotificationMonitorService.class);
    }

    public static boolean accessGranted(Context context) {
        if (context == null) return false;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && nm != null) {
                return nm.isNotificationListenerAccessGranted(component(context));
            }
        } catch (Throwable ignored) {}

        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null) return false;
            ComponentName ours = component(context);
            String flat = ours.flattenToString();
            String shortFlat = ours.flattenToShortString();
            return enabled.contains(flat) || enabled.contains(shortFlat) || enabled.contains(context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean componentDeclared(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getServiceInfo(component(context), 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean componentEnabled(Context context) {
        if (context == null) return false;
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName cn = component(context);
            int explicit = pm.getComponentEnabledSetting(cn);
            if (explicit == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    explicit == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    explicit == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            if (explicit == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            ServiceInfo info = pm.getServiceInfo(cn, 0);
            return info.enabled && info.applicationInfo != null && info.applicationInfo.enabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean appNotificationsEnabled(Context context) {
        if (context == null) return false;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }


    public static boolean batteryOptimizationIgnored(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String componentSetting(Context context) {
        if (context == null) return "desconhecido";
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(component(context));
            switch (state) {
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED: return "ENABLED";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED: return "DISABLED";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER: return "DISABLED_USER";
                case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED: return "DISABLED_UNTIL_USED";
                default: return "DEFAULT";
            }
        } catch (Throwable ignored) {
            return "ERRO";
        }
    }

    public static String secureSettingContainsUs(Context context) {
        if (context == null) return "NÃO";
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || enabled.trim().isEmpty()) return "NÃO";
            return enabled.contains(context.getPackageName()) ? "SIM" : "NÃO";
        } catch (Throwable ignored) {
            return "ERRO";
        }
    }

    public static String snapshot(Context context) {
        return "acesso=" + yesNo(accessGranted(context)) +
                " componentDeclared=" + yesNo(componentDeclared(context)) +
                " componentEnabled=" + yesNo(componentEnabled(context)) +
                " componentSetting=" + componentSetting(context) +
                " secureContainsPackage=" + secureSettingContainsUs(context) +
                " appNotifications=" + yesNo(appNotificationsEnabled(context)) +
                " batteryExempt=" + yesNo(batteryOptimizationIgnored(context)) +
                " liveConnected=" + yesNo(NotificationMonitorService.isConnectedNow());
    }

    public static String humanSummary(Context context) {
        return "Acesso às notificações: " + yesNo(accessGranted(context)) + "\n" +
                "Componente declarado: " + yesNo(componentDeclared(context)) + "\n" +
                "Componente habilitado: " + yesNo(componentEnabled(context)) + "\n" +
                "Estado do componente: " + componentSetting(context) + "\n" +
                "Android lista este app no acesso: " + secureSettingContainsUs(context) + "\n" +
                "Notificações do Alerta de Assuntos: " + yesNo(appNotificationsEnabled(context)) + "\n" +
                "Fora da otimização de bateria: " + yesNo(batteryOptimizationIgnored(context)) + "\n" +
                "Listener conectado agora: " + yesNo(NotificationMonitorService.isConnectedNow());
    }

    private static String yesNo(boolean value) {
        return value ? "SIM" : "NÃO";
    }
}
