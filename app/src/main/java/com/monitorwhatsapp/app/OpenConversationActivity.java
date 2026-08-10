package com.monitorwhatsapp.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/** Invisible trampoline Activity used only to replay the captured WhatsApp PendingIntent. */
public class OpenConversationActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        PendingIntent original = null;
        if (incoming != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                original = incoming.getParcelableExtra(ConversationLink.EXTRA_ORIGINAL_ACTION, PendingIntent.class);
            } else {
                original = incoming.getParcelableExtra(ConversationLink.EXTRA_ORIGINAL_ACTION);
            }
        }

        String creator = original == null ? "" : original.getCreatorPackage();
        DiagnosticLog.append(this, "OPEN_CAPTURED_ACTION_ATTEMPT",
                "creator=" + creator + " hasAction=" + (original != null));

        boolean opened = NotificationActionRegistry.send(this, original);
        DiagnosticLog.append(this, opened ? "OPEN_CAPTURED_ACTION_OK" : "OPEN_CAPTURED_ACTION_FAILED",
                "creator=" + creator);

        if (!opened) {
            String pkg = incoming == null ? "com.whatsapp"
                    : incoming.getStringExtra(ConversationLink.EXTRA_SOURCE_PACKAGE);
            if (pkg == null || pkg.isEmpty()) pkg = "com.whatsapp";
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null && !"com.whatsapp".equals(pkg)) {
                launch = getPackageManager().getLaunchIntentForPackage("com.whatsapp");
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                DiagnosticLog.append(this, "OPEN_WHATSAPP_FALLBACK", "package=" + pkg);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
