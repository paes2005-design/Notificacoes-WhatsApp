package com.monitorwhatsapp.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class Occurrence {
    public String id;
    public String monitorId;
    public String monitorName;
    public String group;
    public String sender;
    public String message;
    public long timestamp;
    public String notificationKey;
    public String sourcePackage;
    public String sourceChannelId;
    public String conversationId;
    public boolean directLinkCaptured;

    public Occurrence(String id, String monitorId, String monitorName, String group, String sender,
                      String message, long timestamp, String notificationKey, String sourcePackage,
                      String sourceChannelId, String conversationId, boolean directLinkCaptured) {
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.monitorId = monitorId;
        this.monitorName = monitorName;
        this.group = group;
        this.sender = sender;
        this.message = message;
        this.timestamp = timestamp;
        this.notificationKey = notificationKey;
        this.sourcePackage = sourcePackage;
        this.sourceChannelId = sourceChannelId;
        this.conversationId = conversationId;
        this.directLinkCaptured = directLinkCaptured;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("monitorId", monitorId);
        o.put("monitorName", monitorName);
        o.put("group", group);
        o.put("sender", sender);
        o.put("message", message);
        o.put("timestamp", timestamp);
        o.put("notificationKey", notificationKey);
        o.put("sourcePackage", sourcePackage);
        o.put("sourceChannelId", sourceChannelId);
        o.put("conversationId", conversationId);
        o.put("directLinkCaptured", directLinkCaptured);
        return o;
    }

    public static Occurrence fromJson(JSONObject o) {
        return new Occurrence(
                o.optString("id"),
                o.optString("monitorId"),
                o.optString("monitorName"),
                o.optString("group"),
                o.optString("sender"),
                o.optString("message"),
                o.optLong("timestamp"),
                o.optString("notificationKey"),
                o.optString("sourcePackage", "com.whatsapp"),
                o.optString("sourceChannelId", ""),
                o.optString("conversationId", ""),
                o.optBoolean("directLinkCaptured", false)
        );
    }
}
