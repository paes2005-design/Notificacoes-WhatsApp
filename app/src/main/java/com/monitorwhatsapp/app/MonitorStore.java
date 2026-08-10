package com.monitorwhatsapp.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MonitorStore {
    private static final String PREFS = "monitor_whatsapp_prefs";
    private static final String KEY_RULES = "rules";
    private static final String KEY_OCCURRENCES = "occurrences";
    private static final String KEY_DIAGNOSTIC = "diagnostic";
    private static final String KEY_MESSAGE_CURSORS = "message_cursors_v3";
    private static final int MAX_OCCURRENCES = 300;

    private final SharedPreferences prefs;

    public MonitorStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        seedIfNeeded();
    }

    private void seedIfNeeded() {
        if (prefs.contains(KEY_RULES)) return;
        // Client edition starts clean. The user creates only the monitors they want.
        saveRules(new ArrayList<>());
    }

    public List<MonitorRule> getRules() {
        List<MonitorRule> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_RULES, "[]"));
            for (int i = 0; i < arr.length(); i++) result.add(MonitorRule.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return result;
    }

    public void saveRules(List<MonitorRule> rules) {
        JSONArray arr = new JSONArray();
        try {
            for (MonitorRule rule : rules) arr.put(rule.toJson());
            prefs.edit().putString(KEY_RULES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void upsertRule(MonitorRule rule) {
        List<MonitorRule> rules = getRules();
        boolean updated = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                rules.set(i, rule);
                updated = true;
                break;
            }
        }
        if (!updated) rules.add(rule);
        saveRules(rules);
    }

    public void deleteRule(String id) {
        List<MonitorRule> rules = getRules();
        rules.removeIf(r -> r.id.equals(id));
        saveRules(rules);
    }

    public List<Occurrence> getOccurrences() {
        List<Occurrence> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_OCCURRENCES, "[]"));
            for (int i = 0; i < arr.length(); i++) result.add(Occurrence.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return result;
    }

    public void addOccurrence(Occurrence occurrence) {
        List<Occurrence> list = getOccurrences();
        list.add(0, occurrence);
        if (list.size() > MAX_OCCURRENCES) list = new ArrayList<>(list.subList(0, MAX_OCCURRENCES));
        JSONArray arr = new JSONArray();
        try {
            for (Occurrence o : list) arr.put(o.toJson());
            prefs.edit().putString(KEY_OCCURRENCES, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void clearOccurrences() {
        prefs.edit().remove(KEY_OCCURRENCES).apply();
    }

    public long getCursorTimestamp(String conversationKey) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MESSAGE_CURSORS, "{}"));
            JSONObject cursor = root.optJSONObject(conversationKey);
            return cursor == null ? 0L : cursor.optLong("timestamp", 0L);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public String getCursorSignature(String conversationKey) {
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MESSAGE_CURSORS, "{}"));
            JSONObject cursor = root.optJSONObject(conversationKey);
            return cursor == null ? "" : cursor.optString("signature", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    public synchronized void setCursor(String conversationKey, long timestamp, String signature) {
        if (conversationKey == null || conversationKey.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY_MESSAGE_CURSORS, "{}"));
            JSONObject cursor = new JSONObject();
            cursor.put("timestamp", timestamp);
            cursor.put("signature", signature == null ? "" : signature);
            root.put(conversationKey, cursor);
            prefs.edit().putString(KEY_MESSAGE_CURSORS, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void setDiagnostic(String diagnostic) {
        prefs.edit().putString(KEY_DIAGNOSTIC, diagnostic == null ? "" : diagnostic).apply();
    }

    public String getDiagnostic() {
        return prefs.getString(KEY_DIAGNOSTIC, "Nenhuma notificação do WhatsApp capturada ainda.");
    }
}
