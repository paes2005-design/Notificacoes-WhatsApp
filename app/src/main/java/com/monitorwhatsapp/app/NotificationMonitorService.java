package com.monitorwhatsapp.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NotificationMonitorService extends NotificationListenerService {
    private static final String WHATSAPP = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    private static final long DEDUPE_WINDOW_MS = 3_000L;
    private static final long REBIND_THROTTLE_MS = 5_000L;
    private static final long HARD_REBIND_THROTTLE_MS = 30_000L;
    private static final long RECENT_SUPPRESS_MS = 12_000L;

    private static volatile NotificationMonitorService connectedInstance;
    private static volatile long lastRebindRequestAt;
    private static volatile long lastHardRebindRequestAt;

    private final Map<String, Long> seenFingerprints = new HashMap<>();
    private final Map<String, Long> callbackFingerprints = new HashMap<>();
    private final Set<String> pendingCancelledKeys = new HashSet<>();
    private final Map<String, Long> recentSuppressedGroupKeys = new HashMap<>();
    private final Map<String, Long> recentSuppressedShortcuts = new HashMap<>();
    private final Map<String, Long> recentSuppressedGroups = new HashMap<>();

    private static class MessageData {
        final String sender;
        final String text;
        final long timestamp;

        MessageData(String sender, String text, long timestamp) {
            this.sender = sender == null ? "" : sender;
            this.text = text == null ? "" : text;
            this.timestamp = timestamp;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AlertChannels.ensure(this);
        DiagnosticLog.markServiceCreated(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connectedInstance = this;
        DiagnosticLog.markListenerConnected(this);
        try {
            StatusBarNotification[] active = getActiveNotifications();
            DiagnosticLog.append(this, "ACTIVE_SNAPSHOT", "notificações ativas=" + (active == null ? 0 : active.length) +
                    " / V3 não transforma esse snapshot em histórico");
        } catch (Exception e) {
            DiagnosticLog.error(this, "activeSnapshot", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (connectedInstance == this) connectedInstance = null;
        DiagnosticLog.markListenerDisconnected(this);
        super.onListenerDisconnected();

        new Handler(Looper.getMainLooper()).postDelayed(() ->
                requestReconnect(getApplicationContext(), "onListenerDisconnected"), 1200L);
    }

    @Override
    public void onDestroy() {
        if (connectedInstance == this) connectedInstance = null;
        DiagnosticLog.markServiceDestroyed(this);
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handlePosted(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        handlePosted(sbn);
    }

    private void handlePosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (isDuplicateCallback(sbn)) return;

        String pkg = safe(sbn.getPackageName());
        DiagnosticLog.markRawNotification(this, pkg);
        if (!WHATSAPP.equals(pkg) && !WHATSAPP_BUSINESS.equals(pkg)) return;

        try {
            Notification n = sbn.getNotification();
            Bundle e = n.extras == null ? new Bundle() : n.extras;

            String title = safe(e.getCharSequence(Notification.EXTRA_TITLE));
            String text = safe(e.getCharSequence(Notification.EXTRA_TEXT));
            String bigText = safe(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String subText = safe(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
            String infoText = safe(e.getCharSequence(Notification.EXTRA_INFO_TEXT));
            String summaryText = safe(e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            String conversationTitle = safe(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? safe(n.getChannelId()) : "";
            String shortcutId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? safe(n.getShortcutId()) : "";
            boolean groupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

            String groupHint = !conversationTitle.isEmpty()
                    ? conversationTitle
                    : (!subText.isEmpty() ? subText : title);

            PendingIntent originalAction = n.contentIntent;
            List<MessageData> allMessages = extractMessages(n, title, text, bigText, sbn.getPostTime());
            if (allMessages.isEmpty()) {
                allMessages.add(new MessageData(
                        extractSenderFromFallback(groupHint, title, text),
                        !bigText.isEmpty() ? bigText : text,
                        sbn.getPostTime()));
            }

            MessageData newest = newest(allMessages);
            DiagnosticLog.markWhatsApp(this, groupHint, newest == null ? "" : newest.sender,
                    newest == null ? text : newest.text);
            DiagnosticLog.append(this, "WA_NOTIFICATION_POSTED",
                    "key=" + shortKey(sbn.getKey()) +
                            " group=" + groupHint +
                            " title=" + title +
                            " channel=" + channelId +
                            " shortcut=" + shortcutId +
                            " summary=" + groupSummary +
                            " messagesInBundle=" + allMessages.size() +
                            " contentIntent=" + (originalAction != null));

            MonitorStore store = new MonitorStore(this);

            // A summary/residual notification can be recreated by WhatsApp immediately after we dismiss
            // the conversation notification. V0.7 remembers the recently suppressed conversation and
            // removes that replacement too, without blocking normal new child notifications.
            if (groupSummary && isRecentlySuppressed(pkg, sbn.getGroupKey(), shortcutId, groupHint)) {
                requestCancel(sbn, "RESIDUAL_SUMMARY_CANCEL_REQUESTED", groupHint);
                store.setDiagnostic(buildDiagnostic(pkg, title, text, bigText, subText, summaryText,
                        conversationTitle, channelId, shortcutId, sbn.getGroupKey(), true, sbn.isOngoing(),
                        allMessages.size(), 0, originalAction != null, sbn.getKey(), "RESUMO RESIDUAL REMOVIDO"));
                return;
            }

            // Other aggregate summaries are logged but not converted into occurrences.
            if (groupSummary) {
                store.setDiagnostic(buildDiagnostic(pkg, title, text, bigText, subText, summaryText,
                        conversationTitle, channelId, shortcutId, sbn.getGroupKey(), true, sbn.isOngoing(),
                        allMessages.size(), 0, originalAction != null, sbn.getKey(), "RESUMO DE GRUPO"));
                DiagnosticLog.append(this, "WA_GROUP_SUMMARY_SKIPPED", "key=" + shortKey(sbn.getKey()));
                return;
            }

            String conversationKey = conversationKey(pkg, shortcutId, groupHint, title);
            long cursorBefore = store.getCursorTimestamp(conversationKey);
            String signatureBefore = store.getCursorSignature(conversationKey);
            List<MessageData> freshMessages = selectFreshMessages(allMessages, cursorBefore, signatureBefore);

            long newestTimestamp = cursorBefore;
            String newestSignature = signatureBefore;
            if (newest != null) {
                newestTimestamp = Math.max(cursorBefore, newest.timestamp);
                newestSignature = signature(newest);
            }

            List<MonitorRule> rules = store.getRules();
            List<MonitorRule> matchingRules = new ArrayList<>();
            boolean rulesChanged = false;
            boolean suppressOriginal = false;

            String groupHaystack = groupHint + " | " + title + " | " + subText + " | " + conversationTitle;
            for (MonitorRule rule : rules) {
                if (!rule.active) continue;
                if (!TextMatcher.groupMatches(groupHaystack, rule.group)) continue;
                matchingRules.add(rule);
                rule.rememberSource(pkg, channelId, shortcutId, groupHint, System.currentTimeMillis());
                rulesChanged = true;
                // V0.7: every active rule tied to a specific group suppresses that group's original
                // WhatsApp notification. This no longer depends on an old persisted checkbox value.
                if (rule.group != null && !rule.group.trim().isEmpty()) {
                    suppressOriginal = true;
                }
            }

            DiagnosticLog.append(this, "WA_PARSED",
                    "group=" + groupHint +
                            " cursorBefore=" + cursorBefore +
                            " bundle=" + allMessages.size() +
                            " fresh=" + freshMessages.size() +
                            " matchingRules=" + matchingRules.size() +
                            " suppressOriginal=" + suppressOriginal);

            for (MessageData candidate : freshMessages) {
                String candidateSignature = signature(candidate);
                DiagnosticLog.append(this, "NEW_MESSAGE",
                        "group=" + groupHint + " sender=" + candidate.sender +
                                " ts=" + candidate.timestamp + " text=" + candidate.text);

                for (MonitorRule rule : matchingRules) {
                    // Client edition: optional sender filter. If filled, only this sender can trigger the alert.
                    if (rule.sender != null && !rule.sender.trim().isEmpty()
                            && !TextMatcher.groupMatches(candidate.sender, rule.sender)) {
                        DiagnosticLog.append(this, "RULE_SENDER_NO_MATCH",
                                "rule=" + rule.name + " expected=" + rule.sender + " actual=" + candidate.sender);
                        continue;
                    }

                    String haystack = String.join(" | ",
                            title, candidate.sender, candidate.text, text, bigText,
                            subText, infoText, summaryText, conversationTitle);

                    String matched = null;
                    if (rule.keywords == null || rule.keywords.isEmpty()) {
                        if (rule.sender != null && !rule.sender.trim().isEmpty()) matched = "remetente:" + rule.sender;
                    } else {
                        for (String keyword : rule.keywords) {
                            if (TextMatcher.containsKeyword(haystack, keyword)) {
                                matched = keyword;
                                break;
                            }
                        }
                    }
                    if (matched == null) {
                        DiagnosticLog.append(this, "RULE_NO_MATCH", "rule=" + rule.name + " textSig=" + candidateSignature);
                        continue;
                    }

                    String fingerprint = rule.id + "|" + conversationKey + "|" + candidate.timestamp + "|" + candidateSignature + "|" + matched;
                    if (isDuplicateMessage(fingerprint)) {
                        DiagnosticLog.append(this, "DUPLICATE_SKIPPED", "rule=" + rule.name + " keyword=" + matched);
                        continue;
                    }

                    Occurrence occurrence = new Occurrence(
                            null,
                            rule.id,
                            rule.name,
                            groupHint,
                            candidate.sender,
                            candidate.text,
                            candidate.timestamp,
                            sbn.getKey(),
                            pkg,
                            channelId,
                            shortcutId,
                            originalAction != null
                    );

                    store.addOccurrence(occurrence);
                    if (originalAction != null) {
                        NotificationActionRegistry.register(occurrence, originalAction);
                    }
                    DiagnosticLog.markMatch(this, rule.name, matched);
                    DiagnosticLog.append(this, "RULE_MATCH", "rule=" + rule.name + " keyword=" + matched + " group=" + groupHint);
                    showImportantNotification(rule, occurrence, matched, originalAction);
                    DiagnosticLog.markAlertCreated(this);
                    DiagnosticLog.append(this, "ALERT_CREATED", "occurrence=" + occurrence.id + " rule=" + rule.name);
                }
            }

            // Persist message cursor independently from the visible history. Clearing history must not touch this.
            store.setCursor(conversationKey, newestTimestamp, newestSignature);
            DiagnosticLog.append(this, "CURSOR_SAVED",
                    "conversation=" + conversationKey + " timestamp=" + newestTimestamp + " signature=" + newestSignature);

            if (rulesChanged) store.saveRules(rules);

            if (suppressOriginal) {
                rememberSuppressed(pkg, sbn.getGroupKey(), shortcutId, groupHint);
                requestCancel(sbn, "ORIGINAL_CANCEL_REQUESTED", groupHint);
                cancelConversationResiduals(pkg, sbn.getGroupKey(), shortcutId, groupHint, sbn.getKey(), "immediate");
                scheduleResidualSweep(pkg, sbn.getGroupKey(), shortcutId, groupHint, sbn.getKey());
            }

            store.setDiagnostic(buildDiagnostic(pkg, title, text, bigText, subText, summaryText,
                    conversationTitle, channelId, shortcutId, sbn.getGroupKey(), false, sbn.isOngoing(),
                    allMessages.size(), freshMessages.size(), originalAction != null, sbn.getKey(),
                    "cursor=" + cursorBefore + " -> " + newestTimestamp + " / regras=" + matchingRules.size() +
                            " / cancelar=" + suppressOriginal));
        } catch (Exception ex) {
            DiagnosticLog.error(this, "handlePosted", ex);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;
        String pkg = safe(sbn.getPackageName());
        if (!WHATSAPP.equals(pkg) && !WHATSAPP_BUSINESS.equals(pkg)) return;
        boolean ours = pendingCancelledKeys.remove(sbn.getKey());
        if (ours) {
            DiagnosticLog.markCancelConfirmed(this);
            DiagnosticLog.append(this, "ORIGINAL_CANCEL_CONFIRMED", "key=" + shortKey(sbn.getKey()));
        } else {
            DiagnosticLog.append(this, "WA_NOTIFICATION_REMOVED", "key=" + shortKey(sbn.getKey()) + " / remoção externa ou pelo WhatsApp");
        }
    }

    private void requestCancel(StatusBarNotification sbn, String event, String groupHint) {
        if (sbn == null || sbn.getKey() == null) return;
        try {
            pendingCancelledKeys.add(sbn.getKey());
            DiagnosticLog.markCancelRequested(this);
            DiagnosticLog.append(this, event,
                    "key=" + shortKey(sbn.getKey()) + " group=" + safe(groupHint));
            cancelNotification(sbn.getKey());
        } catch (Throwable t) {
            pendingCancelledKeys.remove(sbn.getKey());
            DiagnosticLog.error(this, "requestCancel:" + event, t);
        }
    }

    private void rememberSuppressed(String pkg, String groupKey, String shortcutId, String groupHint) {
        long expires = System.currentTimeMillis() + RECENT_SUPPRESS_MS;
        if (!safe(groupKey).isEmpty()) recentSuppressedGroupKeys.put(pkg + "|" + groupKey, expires);
        if (!safe(shortcutId).isEmpty()) recentSuppressedShortcuts.put(pkg + "|" + shortcutId, expires);
        String normalized = TextMatcher.normalize(groupHint);
        if (!normalized.isEmpty()) recentSuppressedGroups.put(pkg + "|" + normalized, expires);
        cleanupSuppressionMemory();
    }

    private boolean isRecentlySuppressed(String pkg, String groupKey, String shortcutId, String groupHint) {
        cleanupSuppressionMemory();
        long now = System.currentTimeMillis();
        Long e = recentSuppressedGroupKeys.get(pkg + "|" + safe(groupKey));
        if (!safe(groupKey).isEmpty() && e != null && e > now) return true;
        e = recentSuppressedShortcuts.get(pkg + "|" + safe(shortcutId));
        if (!safe(shortcutId).isEmpty() && e != null && e > now) return true;
        String normalized = TextMatcher.normalize(groupHint);
        e = recentSuppressedGroups.get(pkg + "|" + normalized);
        return !normalized.isEmpty() && e != null && e > now;
    }

    private void cleanupSuppressionMemory() {
        long now = System.currentTimeMillis();
        recentSuppressedGroupKeys.entrySet().removeIf(e -> e.getValue() <= now);
        recentSuppressedShortcuts.entrySet().removeIf(e -> e.getValue() <= now);
        recentSuppressedGroups.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private void scheduleResidualSweep(String pkg, String groupKey, String shortcutId,
                                       String groupHint, String childKey) {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(() -> cancelConversationResiduals(pkg, groupKey, shortcutId, groupHint, childKey, "150ms"), 150L);
        h.postDelayed(() -> cancelConversationResiduals(pkg, groupKey, shortcutId, groupHint, childKey, "900ms"), 900L);
    }

    private void cancelConversationResiduals(String pkg, String groupKey, String shortcutId,
                                             String groupHint, String childKey, String pass) {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            List<String> keys = new ArrayList<>();
            String targetGroup = TextMatcher.normalize(groupHint);
            for (StatusBarNotification candidate : active) {
                if (candidate == null || candidate.getNotification() == null) continue;
                if (!safe(pkg).equals(safe(candidate.getPackageName()))) continue;
                if (safe(childKey).equals(safe(candidate.getKey()))) continue;

                Notification cn = candidate.getNotification();
                Bundle ce = cn.extras == null ? new Bundle() : cn.extras;
                String cTitle = safe(ce.getCharSequence(Notification.EXTRA_TITLE));
                String cSub = safe(ce.getCharSequence(Notification.EXTRA_SUB_TEXT));
                String cConversation = safe(ce.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
                String cHint = !cConversation.isEmpty() ? cConversation : (!cSub.isEmpty() ? cSub : cTitle);
                String cShortcut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? safe(cn.getShortcutId()) : "";
                boolean summary = (cn.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                boolean sameShortcut = !safe(shortcutId).isEmpty() && safe(shortcutId).equals(cShortcut);
                boolean sameConversation = !targetGroup.isEmpty() && TextMatcher.groupMatches(cHint, groupHint);
                boolean sameSummaryGroup = summary && !safe(groupKey).isEmpty()
                        && safe(groupKey).equals(safe(candidate.getGroupKey()));

                if (sameShortcut || sameConversation || sameSummaryGroup) {
                    keys.add(candidate.getKey());
                    pendingCancelledKeys.add(candidate.getKey());
                    DiagnosticLog.markCancelRequested(this);
                    DiagnosticLog.append(this, "RESIDUAL_CANCEL_REQUESTED",
                            "pass=" + pass + " key=" + shortKey(candidate.getKey()) +
                                    " shortcut=" + cShortcut + " hint=" + cHint + " summary=" + summary);
                }
            }
            if (!keys.isEmpty()) cancelNotifications(keys.toArray(new String[0]));
        } catch (Throwable t) {
            DiagnosticLog.error(this, "cancelConversationResiduals:" + pass, t);
        }
    }

    public static boolean isConnectedNow() {
        return connectedInstance != null;
    }

    public static boolean requestReconnect(Context context, String reason) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (!ListenerHealth.componentDeclared(context) || !ListenerHealth.componentEnabled(context)) {
            DiagnosticLog.append(context, "RECONNECT_BLOCKED", "componente ausente/desabilitado / " + ListenerHealth.snapshot(context));
            return false;
        }
        if (!ListenerHealth.accessGranted(context)) {
            DiagnosticLog.append(context, "RECONNECT_BLOCKED", "acesso às notificações NÃO concedido / " + ListenerHealth.snapshot(context));
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastRebindRequestAt < REBIND_THROTTLE_MS) {
            DiagnosticLog.append(context, "RECONNECT_THROTTLED", "motivo=" + (reason == null ? "manual" : reason));
            return false;
        }
        lastRebindRequestAt = now;
        try {
            DiagnosticLog.markReconnectRequested(context, reason == null ? "manual" : reason);
            NotificationListenerService.requestRebind(new ComponentName(context, NotificationMonitorService.class));
            return true;
        } catch (Exception e) {
            DiagnosticLog.error(context, "requestRebind", e);
            return false;
        }
    }


    public static boolean requestHardReconnect(Context context, String reason) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        if (!ListenerHealth.componentDeclared(context) || !ListenerHealth.componentEnabled(context)) {
            DiagnosticLog.append(context, "HARD_RECONNECT_BLOCKED", "componente ausente/desabilitado / " + ListenerHealth.snapshot(context));
            return false;
        }
        if (!ListenerHealth.accessGranted(context)) {
            DiagnosticLog.append(context, "HARD_RECONNECT_BLOCKED", "acesso às notificações NÃO concedido / " + ListenerHealth.snapshot(context));
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastHardRebindRequestAt < HARD_REBIND_THROTTLE_MS) {
            DiagnosticLog.append(context, "HARD_RECONNECT_THROTTLED", "motivo=" + (reason == null ? "automatico" : reason));
            return false;
        }
        lastHardRebindRequestAt = now;
        ComponentName component = new ComponentName(context, NotificationMonitorService.class);
        String why = reason == null ? "automatico" : reason;
        try {
            DiagnosticLog.append(context, "HARD_RECONNECT_REQUESTED", "motivo=" + why + " / " + ListenerHealth.snapshot(context));
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationListenerService.requestUnbind(component);
                DiagnosticLog.append(context, "HARD_UNBIND_REQUESTED", "api=" + Build.VERSION.SDK_INT);
            } else if (connectedInstance != null) {
                connectedInstance.requestUnbind();
                DiagnosticLog.append(context, "HARD_UNBIND_REQUESTED", "instance api=" + Build.VERSION.SDK_INT);
            }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    NotificationListenerService.requestRebind(component);
                    DiagnosticLog.append(context, "HARD_REBIND_SENT", "motivo=" + why);
                } catch (Throwable t) {
                    DiagnosticLog.error(context, "hardRequestRebind", t);
                }
            }, 1200L);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.error(context, "requestHardReconnect", t);
            try {
                NotificationListenerService.requestRebind(component);
                DiagnosticLog.append(context, "HARD_REBIND_FALLBACK_SENT", "motivo=" + why);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private boolean isDuplicateCallback(StatusBarNotification sbn) {
        long now = System.currentTimeMillis();
        String fp = safe(sbn.getKey()) + "|" + sbn.getPostTime() + "|" + sbn.getNotification().when;
        Long previous = callbackFingerprints.put(fp, now);
        cleanupMap(callbackFingerprints, now, 10_000L);
        return previous != null && now - previous < 1000L;
    }

    private boolean isDuplicateMessage(String fingerprint) {
        long now = System.currentTimeMillis();
        Long previous = seenFingerprints.put(fingerprint, now);
        cleanupMap(seenFingerprints, now, 60_000L);
        return previous != null && now - previous < DEDUPE_WINDOW_MS;
    }

    private void cleanupMap(Map<String, Long> map, long now, long maxAge) {
        Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > maxAge) it.remove();
        }
    }

    private List<MessageData> selectFreshMessages(List<MessageData> all, long cursorTimestamp, String cursorSignature) {
        List<MessageData> fresh = new ArrayList<>();
        if (all == null || all.isEmpty()) return fresh;

        if (cursorTimestamp <= 0L) {
            // First capture: WhatsApp often includes a bundle of recent messages. Only the newest one is new for us.
            MessageData newest = newest(all);
            if (newest != null) fresh.add(newest);
            return fresh;
        }

        for (MessageData m : all) {
            if (m.timestamp > cursorTimestamp) fresh.add(m);
        }

        if (fresh.isEmpty()) {
            MessageData newest = newest(all);
            if (newest != null && !signature(newest).equals(cursorSignature)) {
                // OEM/WhatsApp variants sometimes reuse timestamps. Signature protects us from missing a changed message.
                fresh.add(newest);
            }
        }
        return fresh;
    }

    private MessageData newest(List<MessageData> all) {
        if (all == null || all.isEmpty()) return null;
        MessageData newest = all.get(0);
        for (MessageData m : all) {
            if (m.timestamp >= newest.timestamp) newest = m;
        }
        return newest;
    }

    private String conversationKey(String pkg, String shortcutId, String groupHint, String title) {
        String discriminator = !shortcutId.isEmpty() ? shortcutId : (!groupHint.isEmpty() ? groupHint : title);
        return pkg + "|" + TextMatcher.normalize(discriminator);
    }

    private String signature(MessageData m) {
        return TextMatcher.normalize((m == null ? "" : m.sender) + "|" + (m == null ? "" : m.text));
    }

    private List<MessageData> extractMessages(Notification notification, String title, String text,
                                              String bigText, long fallbackTimestamp) {
        List<MessageData> result = new ArrayList<>();
        try {
            Parcelable[] bundles = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            List<Notification.MessagingStyle.Message> parsed =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
            for (Notification.MessagingStyle.Message message : parsed) {
                String body = safe(message.getText());
                if (body.isEmpty()) continue;

                String sender = "";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Person person = message.getSenderPerson();
                    if (person != null) sender = safe(person.getName());
                }
                if (sender.isEmpty()) sender = safe(message.getSender());
                long ts = message.getTimestamp() > 0 ? message.getTimestamp() : fallbackTimestamp;
                result.add(new MessageData(sender, body, ts));
            }
        } catch (Exception e) {
            DiagnosticLog.error(this, "extractMessagingStyle", e);
        }

        if (result.isEmpty()) {
            String body = !bigText.isEmpty() ? bigText : text;
            String sender = extractSenderFromFallback("", title, body);
            if (!body.isEmpty()) result.add(new MessageData(sender, stripSenderPrefix(body, sender), fallbackTimestamp));
        }
        return result;
    }

    private String extractSenderFromFallback(String groupHint, String title, String body) {
        if (body != null) {
            int colon = body.indexOf(':');
            if (colon > 0 && colon < 80) {
                String candidate = body.substring(0, colon).trim();
                if (!candidate.isEmpty() && !TextMatcher.normalize(candidate).equals(TextMatcher.normalize(groupHint))) {
                    return candidate;
                }
            }
        }
        if (title != null && !title.isEmpty() && !TextMatcher.normalize(title).equals(TextMatcher.normalize(groupHint))) {
            return title;
        }
        return "";
    }

    private String stripSenderPrefix(String body, String sender) {
        if (body == null) return "";
        if (sender == null || sender.isEmpty()) return body;
        String prefix = sender + ":";
        if (body.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return body.substring(prefix.length()).trim();
        }
        return body;
    }

    private void showImportantNotification(MonitorRule rule, Occurrence occurrence, String matched,
                                           PendingIntent originalAction) {
        AlertChannels.ensure(this);

        PendingIntent destination = ConversationLink.create(this, occurrence, originalAction);
        if (destination == null) {
            Intent intent = new Intent(this, ClientMainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            destination = PendingIntent.getActivity(
                    this,
                    Math.abs((rule.id + occurrence.timestamp).hashCode()),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        String line = occurrence.message == null || occurrence.message.isEmpty()
                ? "Palavra encontrada: " + matched
                : occurrence.message;
        String senderPrefix = occurrence.sender == null || occurrence.sender.isEmpty()
                ? "" : occurrence.sender + ": ";

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, AlertChannels.CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_app)
                .setContentTitle("🔔 " + rule.name + " encontrado")
                .setContentText(senderPrefix + line)
                .setStyle(new Notification.BigTextStyle().bigText(senderPrefix + line))
                .setSubText(occurrence.group)
                .setAutoCancel(true)
                .setContentIntent(destination)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_MAX);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(Math.abs((occurrence.id + occurrence.timestamp).hashCode()), builder.build());
    }

    public static boolean openActiveNotification(String key) {
        NotificationMonitorService service = connectedInstance;
        if (service == null || key == null || key.isEmpty()) return false;
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active == null) return false;
            for (StatusBarNotification sbn : active) {
                if (!key.equals(sbn.getKey())) continue;
                PendingIntent action = sbn.getNotification().contentIntent;
                if (!NotificationActionRegistry.send(service, action)) return false;
                if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                    service.cancelNotification(key);
                }
                return true;
            }
        } catch (Exception e) {
            DiagnosticLog.error(service, "openActiveNotification", e);
        }
        return false;
    }

    private String buildDiagnostic(String pkg, String title, String text, String bigText, String subText,
                                   String summaryText, String conversationTitle, String channelId,
                                   String shortcutId, String groupKey, boolean groupSummary, boolean ongoing,
                                   int structuredMessages, int freshMessages, boolean directAction,
                                   String notificationKey, String note) {
        String time = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
        return "Última captura: " + time + "\n" +
                "Pacote: " + pkg + "\n" +
                "Título: " + title + "\n" +
                "Texto: " + text + "\n" +
                "Texto expandido: " + bigText + "\n" +
                "Subtexto: " + subText + "\n" +
                "Resumo: " + summaryText + "\n" +
                "Título da conversa: " + conversationTitle + "\n" +
                "Canal Android: " + channelId + "\n" +
                "ID conversa/atalho: " + shortcutId + "\n" +
                "GroupKey: " + groupKey + "\n" +
                "É resumo de grupo: " + (groupSummary ? "SIM" : "NÃO") + "\n" +
                "Mensagens no pacote: " + structuredMessages + "\n" +
                "Mensagens consideradas novas: " + freshMessages + "\n" +
                "Abrir conversa capturado: " + (directAction ? "SIM" : "NÃO") + "\n" +
                "Chave: " + notificationKey + "\n" +
                "Ongoing: " + (ongoing ? "SIM" : "NÃO") + "\n" +
                "V0.7: " + note;
    }

    private String shortKey(String key) {
        if (key == null) return "";
        return key.length() <= 36 ? key : key.substring(0, 36) + "…";
    }

    private String safe(CharSequence value) { return value == null ? "" : value.toString(); }
    private String safe(String value) { return value == null ? "" : value; }
}
