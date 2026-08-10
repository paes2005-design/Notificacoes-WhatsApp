package com.monitorwhatsapp.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MonitorRule {
    public String id;
    public String name;
    public String group;
    public String sender;
    public List<String> keywords;
    public boolean active;
    public boolean suppressOriginal;

    public String lastSourcePackage;
    public String lastChannelId;
    public String lastConversationId;
    public String lastDetectedGroup;
    public long lastDetectedAt;

    public MonitorRule(String id, String name, String group, List<String> keywords, boolean active) {
        this(id, name, group, "", keywords, active, false, "", "", "", "", 0L);
    }

    // Backwards-compatible constructor used by the technical V0.7 screen.
    public MonitorRule(String id, String name, String group, List<String> keywords, boolean active,
                       boolean suppressOriginal, String lastSourcePackage, String lastChannelId,
                       String lastConversationId, String lastDetectedGroup, long lastDetectedAt) {
        this(id, name, group, "", keywords, active, suppressOriginal, lastSourcePackage, lastChannelId,
                lastConversationId, lastDetectedGroup, lastDetectedAt);
    }

    public MonitorRule(String id, String name, String group, String sender, List<String> keywords, boolean active,
                       boolean suppressOriginal, String lastSourcePackage, String lastChannelId,
                       String lastConversationId, String lastDetectedGroup, long lastDetectedAt) {
        this.id = id;
        this.name = name;
        this.group = safe(group);
        this.sender = safe(sender);
        this.keywords = keywords == null ? new ArrayList<>() : keywords;
        this.active = active;
        this.suppressOriginal = suppressOriginal;
        this.lastSourcePackage = safe(lastSourcePackage);
        this.lastChannelId = safe(lastChannelId);
        this.lastConversationId = safe(lastConversationId);
        this.lastDetectedGroup = safe(lastDetectedGroup);
        this.lastDetectedAt = lastDetectedAt;
    }

    public static MonitorRule create(String name, String group, List<String> keywords) {
        return create(name, group, "", keywords);
    }

    public static MonitorRule create(String name, String group, String sender, List<String> keywords) {
        return new MonitorRule(UUID.randomUUID().toString(), name, group, sender, keywords, true,
                !safe(group).trim().isEmpty(), "", "", "", "", 0L);
    }

    public void rememberSource(String sourcePackage, String channelId, String conversationId,
                               String detectedGroup, long detectedAt) {
        this.lastSourcePackage = safe(sourcePackage);
        this.lastChannelId = safe(channelId);
        this.lastConversationId = safe(conversationId);
        this.lastDetectedGroup = safe(detectedGroup);
        this.lastDetectedAt = detectedAt;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("group", group);
        o.put("sender", sender);
        o.put("active", active);
        o.put("suppressOriginal", suppressOriginal);
        o.put("lastSourcePackage", lastSourcePackage);
        o.put("lastChannelId", lastChannelId);
        o.put("lastConversationId", lastConversationId);
        o.put("lastDetectedGroup", lastDetectedGroup);
        o.put("lastDetectedAt", lastDetectedAt);
        JSONArray arr = new JSONArray();
        for (String keyword : keywords) arr.put(keyword);
        o.put("keywords", arr);
        return o;
    }

    public static MonitorRule fromJson(JSONObject o) throws JSONException {
        List<String> words = new ArrayList<>();
        JSONArray arr = o.optJSONArray("keywords");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) words.add(arr.optString(i));
        }
        String group = o.optString("group", "");
        boolean suppress = o.has("suppressOriginal")
                ? o.optBoolean("suppressOriginal", false)
                : !group.trim().isEmpty();
        return new MonitorRule(
                o.optString("id", UUID.randomUUID().toString()),
                o.optString("name", "Monitoramento"),
                group,
                o.optString("sender", ""),
                words,
                o.optBoolean("active", true),
                suppress,
                o.optString("lastSourcePackage", ""),
                o.optString("lastChannelId", ""),
                o.optString("lastConversationId", ""),
                o.optString("lastDetectedGroup", ""),
                o.optLong("lastDetectedAt", 0L)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
