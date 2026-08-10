package com.monitorwhatsapp.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Persists a short-lived Android PendingIntent wrapper around the original WhatsApp action.
 * Unlike the in-memory registry, this wrapper is owned by the Android system and can survive
 * an app process restart while the device remains running.
 */
public final class ConversationLink {
    static final String ACTION_OPEN = "com.monitorwhatsapp.app.OPEN_CAPTURED_CONVERSATION";
    static final String EXTRA_ORIGINAL_ACTION = "original_action";
    static final String EXTRA_SOURCE_PACKAGE = "source_package";

    private ConversationLink() {}

    public static PendingIntent create(Context context, Occurrence occurrence, PendingIntent originalAction) {
        if (context == null || occurrence == null || originalAction == null) return null;
        Intent wrapper = buildIntent(context, occurrence);
        wrapper.putExtra(EXTRA_ORIGINAL_ACTION, originalAction);
        wrapper.putExtra(EXTRA_SOURCE_PACKAGE,
                occurrence.sourcePackage == null || occurrence.sourcePackage.isEmpty()
                        ? "com.whatsapp" : occurrence.sourcePackage);
        return PendingIntent.getActivity(
                context,
                requestCode(occurrence.id),
                wrapper,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static boolean open(Context context, Occurrence occurrence) {
        if (context == null || occurrence == null || occurrence.id == null || occurrence.id.isEmpty()) return false;
        try {
            PendingIntent wrapper = PendingIntent.getActivity(
                    context,
                    requestCode(occurrence.id),
                    buildIntent(context, occurrence),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (wrapper == null) return false;
            wrapper.send();
            return true;
        } catch (PendingIntent.CanceledException ignored) {
            return false;
        }
    }

    private static Intent buildIntent(Context context, Occurrence occurrence) {
        Intent intent = new Intent(context, OpenConversationActivity.class);
        intent.setAction(ACTION_OPEN);
        intent.setData(Uri.parse("alertaassuntos://occurrence/" + Uri.encode(occurrence.id)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    private static int requestCode(String id) {
        return (id == null ? 0 : id.hashCode()) & 0x7fffffff;
    }
}
