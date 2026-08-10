package com.monitorwhatsapp.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent local diagnostic log. The export must never depend on the UI process staying alive. */
public final class DiagnosticLog {
    private static final String PREFS = "diagnostic_state_v5";
    private static final String FILE_NAME = "diagnostic_v5.log";
    private static final String K_FALLBACK_LOG = "fallback_log";
    private static final int FALLBACK_MAX_CHARS = 48_000;
    private static final long MAX_FILE_BYTES = 900_000L;
    private static final long KEEP_BYTES = 450_000L;

    private static final String K_CONNECTED = "listener_connected";
    private static final String K_CONNECTED_AT = "listener_connected_at";
    private static final String K_LAST_DISCONNECTED_AT = "listener_disconnected_at";
    private static final String K_SERVICE_ALIVE = "service_alive";
    private static final String K_SERVICE_CREATED_AT = "service_created_at";
    private static final String K_SERVICE_DESTROYED_AT = "service_destroyed_at";
    private static final String K_SERVICE_CREATES = "service_creates";
    private static final String K_ALL_EVENTS = "all_notification_events";
    private static final String K_WA_EVENTS = "whatsapp_events";
    private static final String K_MATCHES = "keyword_matches";
    private static final String K_ALERTS = "alerts_created";
    private static final String K_CANCEL_REQUESTS = "cancel_requests";
    private static final String K_CANCEL_CONFIRMED = "cancel_confirmed";
    private static final String K_DISCONNECTS = "listener_disconnects";
    private static final String K_RECONNECTS = "reconnect_requests";
    private static final String K_RECONNECT_CHECKS = "reconnect_checks";
    private static final String K_LAST_RECONNECT_RESULT = "last_reconnect_result";
    private static final String K_ERRORS = "errors";
    private static final String K_LAST_EVENT_AT = "last_event_at";
    private static final String K_LAST_EVENT_PACKAGE = "last_event_package";
    private static final String K_LAST_WA_AT = "last_whatsapp_at";
    private static final String K_LAST_GROUP = "last_group";
    private static final String K_LAST_SENDER = "last_sender";
    private static final String K_LAST_TEXT = "last_text";
    private static final String K_LAST_RULE = "last_rule";

    private DiagnosticLog() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void append(Context context, String event, String detail) {
        if (context == null) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String line = time + " | " + sanitize(event) + (sanitize(detail).isEmpty() ? "" : " | " + sanitize(detail)) + "\n";
        boolean written = false;
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { out.getFD().sync(); } catch (Exception ignored) {}
            }
            trimIfNeeded(file);
            written = true;
        } catch (Exception ignored) {}

        // Secondary ring buffer. Even if internal file I/O/provider export fails, diagnostics survive.
        try {
            SharedPreferences p = prefs(context);
            String old = p.getString(K_FALLBACK_LOG, "");
            String next = old + line;
            if (next.length() > FALLBACK_MAX_CHARS) next = next.substring(next.length() - FALLBACK_MAX_CHARS);
            p.edit().putString(K_FALLBACK_LOG, next).commit();
        } catch (Exception ignored) {}

        if (!written) {
            try {
                SharedPreferences p = prefs(context);
                p.edit().putLong(K_ERRORS, p.getLong(K_ERRORS, 0) + 1).commit();
            } catch (Exception ignored) {}
        }
    }

    public static void markServiceCreated(Context c) {
        SharedPreferences p = prefs(c); long now = System.currentTimeMillis();
        p.edit().putBoolean(K_SERVICE_ALIVE, true).putLong(K_SERVICE_CREATED_AT, now)
                .putLong(K_SERVICE_CREATES, p.getLong(K_SERVICE_CREATES, 0) + 1).commit();
        append(c, "SERVICE_CREATED", ListenerHealth.snapshot(c));
    }

    public static void markServiceDestroyed(Context c) {
        prefs(c).edit().putBoolean(K_SERVICE_ALIVE, false).putBoolean(K_CONNECTED, false)
                .putLong(K_SERVICE_DESTROYED_AT, System.currentTimeMillis()).commit();
        append(c, "SERVICE_DESTROYED", ListenerHealth.snapshot(c));
    }

    public static void markListenerConnected(Context c) {
        prefs(c).edit().putBoolean(K_CONNECTED, true).putLong(K_CONNECTED_AT, System.currentTimeMillis()).commit();
        append(c, "LISTENER_CONNECTED", ListenerHealth.snapshot(c));
    }

    public static void markListenerDisconnected(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putBoolean(K_CONNECTED, false).putLong(K_LAST_DISCONNECTED_AT, System.currentTimeMillis())
                .putLong(K_DISCONNECTS, p.getLong(K_DISCONNECTS, 0) + 1).commit();
        append(c, "LISTENER_DISCONNECTED", ListenerHealth.snapshot(c));
    }

    public static void markReconnectRequested(Context c, String reason) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(K_RECONNECTS, p.getLong(K_RECONNECTS, 0) + 1).commit();
        append(c, "RECONNECT_REQUESTED", "motivo=" + safe(reason) + " / " + ListenerHealth.snapshot(c));
    }

    public static void markReconnectCheck(Context c, boolean connected, String reason) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(K_RECONNECT_CHECKS, p.getLong(K_RECONNECT_CHECKS, 0) + 1)
                .putString(K_LAST_RECONNECT_RESULT, connected ? "CONECTADO" : "AINDA_DESCONECTADO").commit();
        append(c, connected ? "RECONNECT_CONFIRMED" : "RECONNECT_TIMEOUT",
                "motivo=" + safe(reason) + " / " + ListenerHealth.snapshot(c));
    }

    public static void markRawNotification(Context c, String pkg) {
        SharedPreferences p = prefs(c); long now = System.currentTimeMillis();
        p.edit().putLong(K_ALL_EVENTS, p.getLong(K_ALL_EVENTS, 0) + 1).putLong(K_LAST_EVENT_AT, now)
                .putString(K_LAST_EVENT_PACKAGE, safe(pkg)).commit();
        append(c, "RAW_NOTIFICATION", "pkg=" + safe(pkg));
    }

    public static void markWhatsApp(Context c, String group, String sender, String text) {
        SharedPreferences p = prefs(c);
        p.edit().putLong(K_WA_EVENTS, p.getLong(K_WA_EVENTS, 0) + 1).putLong(K_LAST_WA_AT, System.currentTimeMillis())
                .putString(K_LAST_GROUP, safe(group)).putString(K_LAST_SENDER, safe(sender)).putString(K_LAST_TEXT, safe(text)).commit();
        append(c, "WHATSAPP_EVENT", "group=" + safe(group) + " sender=" + safe(sender) + " text=" + limit(text, 220));
    }

    public static void markMatch(Context c, String rule, String keyword) {
        SharedPreferences p = prefs(c);
        String value = safe(rule) + (keyword == null || keyword.isEmpty() ? "" : " (" + keyword + ")");
        p.edit().putLong(K_MATCHES, p.getLong(K_MATCHES, 0) + 1).putString(K_LAST_RULE, value).commit();
        append(c, "RULE_MATCH_COUNTER", value);
    }

    public static void markAlertCreated(Context c) {
        SharedPreferences p = prefs(c); p.edit().putLong(K_ALERTS, p.getLong(K_ALERTS, 0) + 1).commit();
        append(c, "ALERT_CREATED_COUNTER", "total=" + p.getLong(K_ALERTS, 0));
    }

    public static void markCancelRequested(Context c) {
        SharedPreferences p = prefs(c); p.edit().putLong(K_CANCEL_REQUESTS, p.getLong(K_CANCEL_REQUESTS, 0) + 1).commit();
        append(c, "CANCEL_REQUEST_COUNTER", "total=" + p.getLong(K_CANCEL_REQUESTS, 0));
    }

    public static void markCancelConfirmed(Context c) {
        SharedPreferences p = prefs(c); p.edit().putLong(K_CANCEL_CONFIRMED, p.getLong(K_CANCEL_CONFIRMED, 0) + 1).commit();
        append(c, "CANCEL_CONFIRMED_COUNTER", "total=" + p.getLong(K_CANCEL_CONFIRMED, 0));
    }

    public static void error(Context c, String where, Throwable error) {
        SharedPreferences p = prefs(c); p.edit().putLong(K_ERRORS, p.getLong(K_ERRORS, 0) + 1).commit();
        append(c, "ERROR", "onde=" + where + " tipo=" + (error == null ? "desconhecido" : error.getClass().getSimpleName()) +
                " mensagem=" + (error == null ? "" : safe(error.getMessage())));
    }

    public static String summary(Context c, boolean liveConnected) {
        SharedPreferences p = prefs(c);
        File f = new File(c.getFilesDir(), FILE_NAME);
        return "=== SAÚDE DO LISTENER ===\n" + ListenerHealth.humanSummary(c) + "\n" +
                "Objeto do serviço ativo: " + yesNo(p.getBoolean(K_SERVICE_ALIVE, false)) + "\n" +
                "Watchdog em execução: " + yesNo(MonitorWatchdogService.isRunning()) + "\n" +
                "Criações do serviço: " + p.getLong(K_SERVICE_CREATES, 0) + "\n" +
                "Última criação: " + formatTime(p.getLong(K_SERVICE_CREATED_AT, 0)) + "\n" +
                "Última destruição: " + formatTime(p.getLong(K_SERVICE_DESTROYED_AT, 0)) + "\n" +
                "Último estado salvo: " + (p.getBoolean(K_CONNECTED, false) ? "CONECTADO" : "DESCONECTADO") + "\n" +
                "Última conexão: " + formatTime(p.getLong(K_CONNECTED_AT, 0)) + "\n" +
                "Última desconexão: " + formatTime(p.getLong(K_LAST_DISCONNECTED_AT, 0)) + "\n" +
                "Arquivo interno de log: " + (f.exists() ? f.length() + " bytes" : "não criado") + "\n\n" +
                "=== EVENTOS ===\n" +
                "Eventos Android recebidos: " + p.getLong(K_ALL_EVENTS, 0) + "\n" +
                "Eventos WhatsApp recebidos: " + p.getLong(K_WA_EVENTS, 0) + "\n" +
                "Palavras encontradas: " + p.getLong(K_MATCHES, 0) + "\n" +
                "Alertas criados: " + p.getLong(K_ALERTS, 0) + "\n" +
                "Cancelamentos solicitados: " + p.getLong(K_CANCEL_REQUESTS, 0) + "\n" +
                "Cancelamentos confirmados: " + p.getLong(K_CANCEL_CONFIRMED, 0) + "\n" +
                "Desconexões: " + p.getLong(K_DISCONNECTS, 0) + "\n" +
                "Reconexões solicitadas: " + p.getLong(K_RECONNECTS, 0) + "\n" +
                "Verificações de reconexão: " + p.getLong(K_RECONNECT_CHECKS, 0) + "\n" +
                "Último resultado da reconexão: " + emptyDash(p.getString(K_LAST_RECONNECT_RESULT, "")) + "\n" +
                "Erros registrados: " + p.getLong(K_ERRORS, 0) + "\n" +
                "Último evento Android: " + formatTime(p.getLong(K_LAST_EVENT_AT, 0)) + " • " + p.getString(K_LAST_EVENT_PACKAGE, "-") + "\n" +
                "Último WhatsApp: " + formatTime(p.getLong(K_LAST_WA_AT, 0)) + "\n" +
                "Grupo: " + emptyDash(p.getString(K_LAST_GROUP, "")) + "\n" +
                "Remetente: " + emptyDash(p.getString(K_LAST_SENDER, "")) + "\n" +
                "Texto: " + emptyDash(p.getString(K_LAST_TEXT, "")) + "\n" +
                "Última regra: " + emptyDash(p.getString(K_LAST_RULE, ""));
    }

    public static synchronized String recent(Context context, int maxLines) {
        String all = combinedLog(context);
        if (all.isEmpty()) return "Nenhum evento técnico registrado ainda.";
        String[] lines = all.split("\\n"); int start = Math.max(0, lines.length - Math.max(1, maxLines));
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) if (!lines[i].trim().isEmpty()) out.append(lines[i]).append('\n');
        return out.toString().trim();
    }

    public static synchronized String exportText(Context context, boolean liveConnected) {
        String events = combinedLog(context);
        if (events.trim().isEmpty()) events = "<< nenhum evento técnico gravado; isso por si só é um diagnóstico >>\n";
        String payload = "ALERTA DE ASSUNTOS CLIENTE - LOG V1.0\n" +
                "Gerado em: " + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n" +
                "Dispositivo: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                " / Android API " + android.os.Build.VERSION.SDK_INT + "\n\n" +
                "=== RESUMO ===\n" + summary(context, liveConnected) + "\n\n" +
                "=== EVENTOS TÉCNICOS ===\n" + events;
        return payload.trim().isEmpty() ? "ALERTA DE ASSUNTOS CLIENTE - LOG V1.0 - ERRO: payload vazio" : payload;
    }

    public static synchronized File writeShareFile(Context context, boolean liveConnected) throws Exception {
        File dir = new File(context.getCacheDir(), "shared_logs");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Não consegui criar pasta de log");
        File file = new File(dir, "Alerta_Assuntos_V5_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt");
        String payload = exportText(context, liveConnected);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8)); out.flush();
            try { out.getFD().sync(); } catch (Exception ignored) {}
        }
        if (!file.exists() || file.length() == 0) throw new IllegalStateException("Arquivo de log foi criado vazio");
        append(context, "LOG_SHARE_FILE_CREATED", "bytes=" + file.length());
        return file;
    }

    public static synchronized void clear(Context context) {
        try { File file = new File(context.getFilesDir(), FILE_NAME); if (file.exists()) file.delete(); } catch (Exception ignored) {}
        SharedPreferences p = prefs(context);
        boolean connected = p.getBoolean(K_CONNECTED, false); boolean alive = p.getBoolean(K_SERVICE_ALIVE, false);
        long connectedAt = p.getLong(K_CONNECTED_AT, 0); long createdAt = p.getLong(K_SERVICE_CREATED_AT, 0);
        p.edit().clear().putBoolean(K_CONNECTED, connected).putBoolean(K_SERVICE_ALIVE, alive)
                .putLong(K_CONNECTED_AT, connectedAt).putLong(K_SERVICE_CREATED_AT, createdAt).commit();
        append(context, "LOG_CLEARED", "log técnico zerado manualmente; histórico não foi alterado");
    }

    private static synchronized String combinedLog(Context context) {
        String file = readAll(context);
        String fallback = prefs(context).getString(K_FALLBACK_LOG, "");
        if (!file.trim().isEmpty()) return file;
        return fallback == null ? "" : fallback;
    }

    private static synchronized String readAll(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME); if (!file.exists()) return "";
            byte[] bytes = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE)];
            try (FileInputStream in = new FileInputStream(file)) {
                int offset = 0; while (offset < bytes.length) { int n = in.read(bytes, offset, bytes.length - offset); if (n < 0) break; offset += n; }
                return new String(bytes, 0, offset, StandardCharsets.UTF_8);
            }
        } catch (Exception e) { return ""; }
    }

    private static void trimIfNeeded(File file) {
        try {
            if (file.length() <= MAX_FILE_BYTES) return;
            byte[] all = new byte[(int) file.length()]; int read;
            try (FileInputStream in = new FileInputStream(file)) { read = in.read(all); }
            if (read <= 0) return; int start = Math.max(0, read - (int) KEEP_BYTES);
            while (start < read && all[start] != '\n') start++; if (start < read) start++;
            try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(all, start, read - start); }
        } catch (Exception ignored) {}
    }

    private static String sanitize(String s) { return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').replace('|', '/').trim(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String limit(String s, int max) { String v = safe(s); return v.length() <= max ? v : v.substring(0, max) + "…"; }
    private static String emptyDash(String s) { return s == null || s.trim().isEmpty() ? "-" : s; }
    private static String yesNo(boolean value) { return value ? "SIM" : "NÃO"; }
    private static String formatTime(long value) { return value <= 0 ? "-" : new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(new Date(value)); }
}
