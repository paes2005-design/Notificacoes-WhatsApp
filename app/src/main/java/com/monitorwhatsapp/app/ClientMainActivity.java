package com.monitorwhatsapp.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Simplified customer-facing screen. Technical engine remains the same as the validated V0.7. */
public class ClientMainActivity extends Activity {
    private static final int BRAND = Color.rgb(23, 107, 91);
    private static final int DARK = Color.rgb(23, 32, 30);
    private static final int MUTED = Color.rgb(99, 112, 109);
    private static final int BG = Color.rgb(246, 248, 247);
    private static final int BORDER = Color.rgb(222, 229, 226);
    private static final int DANGER = Color.rgb(179, 38, 30);
    private static final int WARN = Color.rgb(151, 103, 0);

    private MonitorStore store;
    private LinearLayout content;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MonitorStore(this);
        AlertChannels.ensure(this);
        DiagnosticLog.append(this, "CLIENT_APP_OPEN", "Alerta de Assuntos Cliente v1.0");
        requestNotificationPermissionIfNeeded();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null) return;
        if (ListenerHealth.accessGranted(this)) {
            MonitorWatchdogService.ensureRunning(this);
            if (!NotificationMonitorService.isConnectedNow()) {
                NotificationMonitorService.requestReconnect(this, "ClientMainActivity.onResume");
                handler.postDelayed(this::render, 3000L);
            }
        }
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(text("Alerta de Assuntos", 27, Typeface.BOLD, DARK));
        TextView version = text("Cliente • v1.0", 12, Typeface.BOLD, BRAND);
        version.setPadding(0, dp(2), 0, 0);
        content.addView(version);
        TextView subtitle = text("Receba aviso apenas quando aparecer algo que realmente importa para você.",
                14, Typeface.NORMAL, MUTED);
        subtitle.setPadding(0, dp(5), 0, dp(14));
        content.addView(subtitle);

        renderStatus();
        renderCounters();

        sectionTitle("Meus alertas");
        List<MonitorRule> rules = store.getRules();
        if (rules.isEmpty()) {
            TextView empty = text("Você ainda não criou nenhum alerta. Toque em “Novo alerta” para escolher a conversa, uma pessoa e/ou palavras importantes.",
                    13, Typeface.NORMAL, MUTED);
            addCard(empty);
        } else {
            for (MonitorRule rule : rules) renderRule(rule);
        }

        Button add = primaryButton("+ Novo alerta");
        add.setOnClickListener(v -> showRuleDialog(null));
        content.addView(add, matchWrapWithTop(dp(8)));

        sectionTitle("Últimos alertas recebidos");
        renderOccurrences();

        sectionTitle("Ajuda e suporte");
        LinearLayout support = verticalCard();
        TextView supportText = text("Se o monitor parar ou algum alerta não funcionar, envie o diagnóstico para o suporte.",
                12, Typeface.NORMAL, MUTED);
        support.addView(supportText);
        LinearLayout supportRow = horizontalRow();
        Button share = secondaryButton("Compartilhar diagnóstico");
        share.setOnClickListener(v -> shareDiagnostic());
        Button copy = secondaryButton("Copiar diagnóstico");
        copy.setOnClickListener(v -> copyDiagnostic());
        supportRow.addView(share, flex());
        LinearLayout.LayoutParams gap = flex(); gap.leftMargin = dp(8);
        supportRow.addView(copy, gap);
        support.addView(supportRow, matchWrapWithTop(dp(10)));
        content.addView(support, matchWrap());

        TextView privacy = text("Privacidade: o processamento acontece no próprio aparelho. O aplicativo não pede senha, QR Code nem login do WhatsApp e não possui permissão de Internet.",
                12, Typeface.NORMAL, MUTED);
        privacy.setPadding(0, dp(20), 0, 0);
        content.addView(privacy);

        setContentView(scroll);
    }

    private void renderStatus() {
        boolean access = ListenerHealth.accessGranted(this);
        boolean connected = NotificationMonitorService.isConnectedNow();

        LinearLayout card = verticalCard();
        card.addView(text("STATUS", 12, Typeface.BOLD, MUTED));

        String label;
        int color;
        if (!access) {
            label = "● Monitor precisa ser ativado";
            color = DANGER;
        } else if (!connected) {
            label = "● Reconectando monitor...";
            color = WARN;
        } else {
            label = "● Monitor ativo";
            color = BRAND;
        }
        TextView state = text(label, 18, Typeface.BOLD, color);
        state.setPadding(0, dp(8), 0, dp(5));
        card.addView(state);

        TextView desc = text(!access
                        ? "Para funcionar, o Android precisa permitir que o aplicativo leia as notificações recebidas."
                        : connected
                            ? "Tudo certo. Você pode fechar esta tela; o monitor continua trabalhando em segundo plano."
                            : "A autorização já existe. O aplicativo está tentando reconectar o monitor automaticamente.",
                13, Typeface.NORMAL, DARK);
        card.addView(desc);

        if (!access) {
            Button activate = primaryButton("Ativar monitor");
            activate.setOnClickListener(v -> openNotificationAccess());
            card.addView(activate, matchWrapWithTop(dp(12)));
        } else if (!connected) {
            Button reconnect = primaryButton("Reconectar agora");
            reconnect.setOnClickListener(v -> {
                NotificationMonitorService.requestHardReconnect(this, "cliente - botão reconectar");
                Toast.makeText(this, "Reconexão solicitada.", Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::render, 3000L);
            });
            card.addView(reconnect, matchWrapWithTop(dp(12)));
        }

        Button battery = secondaryButton("Permitir funcionamento em segundo plano");
        battery.setOnClickListener(v -> openBatterySettings());
        card.addView(battery, matchWrapWithTop(dp(8)));

        content.addView(card, matchWrapWithBottom(dp(10)));
    }

    private void renderCounters() {
        List<Occurrence> list = store.getOccurrences();
        long now = System.currentTimeMillis();
        int today = 0, week = 0, month = 0;
        for (Occurrence o : list) {
            long age = now - o.timestamp;
            if (age <= 24L * 60 * 60 * 1000) today++;
            if (age <= 7L * 24 * 60 * 60 * 1000) week++;
            if (age <= 30L * 24 * 60 * 60 * 1000) month++;
        }
        LinearLayout row = horizontalRow();
        row.addView(counterCard("Hoje", today), flex());
        LinearLayout.LayoutParams lp2 = flex(); lp2.leftMargin = dp(8);
        row.addView(counterCard("7 dias", week), lp2);
        LinearLayout.LayoutParams lp3 = flex(); lp3.leftMargin = dp(8);
        row.addView(counterCard("30 dias", month), lp3);
        content.addView(row, matchWrap());
    }

    private View counterCard(String label, int value) {
        LinearLayout box = verticalCard();
        box.setGravity(Gravity.CENTER);
        box.addView(text(String.valueOf(value), 24, Typeface.BOLD, DARK));
        box.addView(text(label, 12, Typeface.NORMAL, MUTED));
        return box;
    }

    private void renderRule(MonitorRule rule) {
        LinearLayout card = verticalCard();
        LinearLayout header = horizontalRow();
        header.addView(text(rule.name, 18, Typeface.BOLD, DARK), flex());
        header.addView(text(rule.active ? "ATIVO" : "PAUSADO", 11, Typeface.BOLD, rule.active ? BRAND : MUTED));
        card.addView(header);

        if (rule.group != null && !rule.group.trim().isEmpty()) {
            TextView group = text("Conversa: " + rule.group, 13, Typeface.NORMAL, DARK);
            group.setPadding(0, dp(7), 0, 0);
            card.addView(group);
        }
        if (rule.sender != null && !rule.sender.trim().isEmpty()) {
            TextView sender = text("Pessoa: " + rule.sender, 13, Typeface.NORMAL, DARK);
            sender.setPadding(0, dp(3), 0, 0);
            card.addView(sender);
        }
        if (rule.keywords != null && !rule.keywords.isEmpty()) {
            TextView words = text("Palavras: " + String.join(", ", rule.keywords), 13, Typeface.NORMAL, DARK);
            words.setPadding(0, dp(3), 0, 0);
            card.addView(words);
        } else if (rule.sender != null && !rule.sender.trim().isEmpty()) {
            TextView any = text("Qualquer mensagem dessa pessoa gera alerta.", 12, Typeface.BOLD, BRAND);
            any.setPadding(0, dp(4), 0, 0);
            card.addView(any);
        }

        if (rule.group != null && !rule.group.trim().isEmpty()) {
            TextView suppress = text("As notificações normais desta conversa são ocultadas; só os alertas configurados chamam sua atenção.",
                    12, Typeface.NORMAL, MUTED);
            suppress.setPadding(0, dp(7), 0, 0);
            card.addView(suppress);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = horizontalRow();
        actions.setPadding(0, dp(12), 0, 0);
        Button edit = compactButton("Editar");
        edit.setOnClickListener(v -> showRuleDialog(rule));
        Button pause = compactButton(rule.active ? "Pausar" : "Ativar");
        pause.setOnClickListener(v -> { rule.active = !rule.active; store.upsertRule(rule); render(); });
        Button delete = compactButton("Excluir");
        delete.setTextColor(DANGER);
        delete.setOnClickListener(v -> confirmDelete(rule));
        actions.addView(edit);
        actions.addView(pause, wrapWithLeft(dp(6)));
        actions.addView(delete, wrapWithLeft(dp(6)));
        hsv.addView(actions);
        card.addView(hsv);
        content.addView(card, matchWrapWithBottom(dp(10)));
    }

    private void showRuleDialog(MonitorRule existing) {
        boolean editing = existing != null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);

        EditText name = field("Nome do alerta", editing ? existing.name : "");
        EditText group = field("Grupo ou conversa (ex.: Rotina Lara)", editing ? existing.group : "");
        EditText sender = field("Pessoa específica (opcional)", editing ? existing.sender : "");
        EditText keywords = field("Palavras importantes, separadas por vírgula (opcional)",
                editing ? String.join(", ", existing.keywords) : "");
        keywords.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        form.addView(name);
        form.addView(group, matchWrapWithTop(dp(8)));
        form.addView(sender, matchWrapWithTop(dp(8)));
        form.addView(keywords, matchWrapWithTop(dp(8)));
        TextView help = text("Você pode usar pessoa + palavras juntas. Se preencher somente a pessoa, qualquer mensagem dela gera alerta. Para ocultar as notificações normais, informe também o grupo/conversa.",
                11, Typeface.NORMAL, MUTED);
        help.setPadding(dp(4), dp(6), dp(4), 0);
        form.addView(help);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "Editar alerta" : "Novo alerta")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String g = group.getText().toString().trim();
            String s = sender.getText().toString().trim();
            List<String> words = parseKeywords(keywords.getText().toString());
            if (n.isEmpty()) { name.setError("Informe um nome"); return; }
            if (s.isEmpty() && words.isEmpty()) {
                keywords.setError("Informe uma pessoa e/ou pelo menos uma palavra");
                return;
            }

            MonitorRule rule;
            if (editing) {
                rule = new MonitorRule(existing.id, n, g, s, words, existing.active,
                        !g.isEmpty(), existing.lastSourcePackage, existing.lastChannelId,
                        existing.lastConversationId, existing.lastDetectedGroup, existing.lastDetectedAt);
                if (!TextMatcher.groupMatches(existing.lastDetectedGroup, g)) {
                    rule.lastSourcePackage = "";
                    rule.lastChannelId = "";
                    rule.lastConversationId = "";
                    rule.lastDetectedGroup = "";
                    rule.lastDetectedAt = 0L;
                }
            } else {
                rule = MonitorRule.create(n, g, s, words);
            }
            store.upsertRule(rule);
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private void renderOccurrences() {
        List<Occurrence> list = store.getOccurrences();
        if (list.isEmpty()) {
            addCard(text("Nenhum alerta recebido ainda.", 13, Typeface.NORMAL, MUTED));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        int limit = Math.min(12, list.size());
        for (int i = 0; i < limit; i++) {
            Occurrence o = list.get(i);
            LinearLayout card = verticalCard();
            card.addView(text(o.monitorName + " • " + fmt.format(new Date(o.timestamp)), 15, Typeface.BOLD, DARK));
            if (o.sender != null && !o.sender.trim().isEmpty()) {
                TextView sender = text(o.sender, 13, Typeface.BOLD, DARK);
                sender.setPadding(0, dp(5), 0, 0);
                card.addView(sender);
            }
            if (o.group != null && !o.group.trim().isEmpty()) {
                TextView group = text(o.group, 12, Typeface.NORMAL, BRAND);
                group.setPadding(0, dp(2), 0, 0);
                card.addView(group);
            }
            TextView msg = text(o.message == null || o.message.isEmpty() ? "Mensagem sem texto disponível" : o.message,
                    13, Typeface.NORMAL, DARK);
            msg.setPadding(0, dp(7), 0, 0);
            card.addView(msg);
            Button open = secondaryButton("Abrir no WhatsApp →");
            open.setOnClickListener(v -> openOccurrence(o));
            card.addView(open, matchWrapWithTop(dp(10)));
            content.addView(card, matchWrapWithBottom(dp(8)));
        }
    }

    private void openOccurrence(Occurrence occurrence) {
        int result = NotificationActionRegistry.open(this, occurrence);
        DiagnosticLog.append(this, "CLIENT_OPEN_CONVERSATION", "occurrence=" + occurrence.id + " result=" + result);
        if (result == NotificationActionRegistry.OPEN_DIRECT) {
            Toast.makeText(this, "Abrindo no WhatsApp.", Toast.LENGTH_SHORT).show();
        } else if (result == NotificationActionRegistry.OPEN_APP_FALLBACK) {
            Toast.makeText(this, "Abrindo o WhatsApp.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Não foi possível abrir o WhatsApp.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(MonitorRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir alerta?")
                .setMessage("“" + rule.name + "” será removido.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> { store.deleteRule(rule.id); render(); })
                .show();
    }

    private List<String> parseKeywords(String raw) {
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) list.add(value);
        }
        return list;
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Abra Configurações > Notificações > Acesso às notificações.", Toast.LENGTH_LONG).show();
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {}
        }
    }

    private void shareDiagnostic() {
        try {
            File file = DiagnosticLog.writeShareFile(this, NotificationMonitorService.isConnectedNow());
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico Alerta de Assuntos Cliente");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartilhar diagnóstico"));
        } catch (Exception e) {
            DiagnosticLog.error(this, "clientShareDiagnostic", e);
            Toast.makeText(this, "Não foi possível compartilhar. Use Copiar diagnóstico.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyDiagnostic() {
        try {
            String payload = DiagnosticLog.exportText(this, NotificationMonitorService.isConnectedNow());
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("clipboard");
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Alerta de Assuntos Cliente", payload));
            Toast.makeText(this, "Diagnóstico copiado.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível copiar o diagnóstico.", Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1101);
        }
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(15);
        e.setSingleLine(false);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        return e;
    }

    private void sectionTitle(String value) {
        TextView t = text(value, 18, Typeface.BOLD, DARK);
        t.setPadding(0, dp(20), 0, dp(10));
        content.addView(t);
    }

    private void addCard(View child) {
        LinearLayout card = verticalCard();
        card.addView(child);
        content.addView(card, matchWrap());
    }

    private LinearLayout verticalCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.WHITE);
        gd.setCornerRadius(dp(14));
        gd.setStroke(dp(1), BORDER);
        box.setBackground(gd);
        return box;
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView text(String value, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setTextColor(color);
        return t;
    }

    private Button primaryButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(BRAND);
        gd.setCornerRadius(dp(10));
        b.setBackground(gd);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    private Button secondaryButton(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(BRAND);
        b.setTextSize(13);
        b.setAllCaps(false);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.WHITE);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), BORDER);
        b.setBackground(gd);
        return b;
    }

    private Button compactButton(String value) {
        Button b = secondaryButton(value);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        return b;
    }

    private LinearLayout.LayoutParams flex() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int top) {
        LinearLayout.LayoutParams p = matchWrap(); p.topMargin = top; return p;
    }

    private LinearLayout.LayoutParams matchWrapWithBottom(int bottom) {
        LinearLayout.LayoutParams p = matchWrap(); p.bottomMargin = bottom; return p;
    }

    private LinearLayout.LayoutParams wrapWithLeft(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = left; return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
