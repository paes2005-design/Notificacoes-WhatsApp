package com.monitorwhatsapp.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT_LOG = 3003;
    private static final int BRAND = Color.rgb(23, 107, 91);
    private static final int DARK = Color.rgb(23, 32, 30);
    private static final int MUTED = Color.rgb(99, 112, 109);
    private static final int BG = Color.rgb(246, 248, 247);
    private static final int BORDER = Color.rgb(222, 229, 226);
    private static final int DANGER = Color.rgb(179, 38, 30);
    private static final int WARN = Color.rgb(151, 103, 0);

    private MonitorStore store;
    private LinearLayout content;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean accessGuidanceShown = false;
    private boolean reconnectHelpShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MonitorStore(this);
        AlertChannels.ensure(this);
        DiagnosticLog.append(this, "APP_OPEN", "Alerta de Assuntos v0.7 aberto");
        DiagnosticLog.append(this, "DEVICE", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " / API=" + android.os.Build.VERSION.SDK_INT);
        DiagnosticLog.append(this, "HEALTH_AT_OPEN", ListenerHealth.snapshot(this));
        requestNotificationPermissionIfNeeded();
        render();
        uiHandler.postDelayed(this::maybeShowAccessGuidance, 500L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) {
            boolean access = ListenerHealth.accessGranted(this);
            DiagnosticLog.append(this, "ACCESS_CHECK", ListenerHealth.snapshot(this));
            if (access) {
                MonitorWatchdogService.ensureRunning(this);
                if (!NotificationMonitorService.isConnectedNow()) {
                    boolean requested = NotificationMonitorService.requestReconnect(this, "MainActivity.onResume");
                    if (requested) scheduleReconnectVerification("MainActivity.onResume", false);
                }
            }
            render();
            if (!access) uiHandler.postDelayed(this::maybeShowAccessGuidance, 450L);
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.removeAllViews();
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Alerta de Assuntos", 27, Typeface.BOLD, DARK);
        content.addView(title);
        TextView version = text("v0.7 • segundo plano + remoção reforçada", 12, Typeface.BOLD, BRAND);
        version.setPadding(0, dp(2), 0, 0);
        content.addView(version);
        TextView subtitle = text("Filtra mensagens importantes do WhatsApp sem você precisar acompanhar o grupo o tempo todo.", 14, Typeface.NORMAL, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        content.addView(subtitle);

        renderStatusCard();
        renderCounters();

        sectionTitle("Monitoramentos");
        List<MonitorRule> rules = store.getRules();
        for (MonitorRule rule : rules) renderRule(rule);

        Button add = primaryButton("+ Novo monitoramento");
        add.setOnClickListener(v -> showRuleDialog(null));
        LinearLayout.LayoutParams addLp = matchWrap();
        addLp.topMargin = dp(8);
        content.addView(add, addLp);

        sectionTitle("Últimas ocorrências");
        renderOccurrences();

        sectionTitle("Diagnóstico V5");

        TextView live = text(DiagnosticLog.summary(this, NotificationMonitorService.isConnectedNow()), 12, Typeface.NORMAL, DARK);
        live.setTextIsSelectable(true);
        addCard(live);

        TextView diagLabel = text("Última notificação WhatsApp interpretada", 12, Typeface.BOLD, MUTED);
        diagLabel.setPadding(0, dp(8), 0, dp(6));
        content.addView(diagLabel);
        TextView diag = text(store.getDiagnostic(), 11, Typeface.NORMAL, DARK);
        diag.setTextIsSelectable(true);
        addCard(diag);

        TextView logLabel = text("Últimos eventos técnicos", 12, Typeface.BOLD, MUTED);
        logLabel.setPadding(0, dp(12), 0, dp(6));
        content.addView(logLabel);
        TextView recent = text(DiagnosticLog.recent(this, 28), 10, Typeface.NORMAL, DARK);
        recent.setTextIsSelectable(true);
        addCard(recent);

        LinearLayout diagActions = horizontalRow();
        Button refresh = secondaryButton("Atualizar");
        refresh.setOnClickListener(v -> render());
        Button reconnect = secondaryButton("Reativar monitor");
        reconnect.setOnClickListener(v -> reactivateMonitor());
        diagActions.addView(refresh, flex());
        LinearLayout.LayoutParams gap = flex();
        gap.leftMargin = dp(8);
        diagActions.addView(reconnect, gap);
        content.addView(diagActions, matchWrapWithTop(dp(8)));

        LinearLayout logActions = horizontalRow();
        Button share = secondaryButton("📤 Compartilhar log");
        share.setOnClickListener(v -> shareDiagnosticLog());
        Button copy = secondaryButton("Copiar diagnóstico");
        copy.setOnClickListener(v -> copyDiagnosticLog());
        logActions.addView(share, flex());
        LinearLayout.LayoutParams gap2 = flex();
        gap2.leftMargin = dp(8);
        logActions.addView(copy, gap2);
        content.addView(logActions, matchWrapWithTop(dp(8)));

        LinearLayout logActions2 = horizontalRow();
        Button export = secondaryButton("Salvar .txt");
        export.setOnClickListener(v -> exportDiagnosticLog());
        Button clearLogs = secondaryButton("Limpar logs");
        clearLogs.setOnClickListener(v -> confirmClearLogs());
        logActions2.addView(export, flex());
        LinearLayout.LayoutParams gap3 = flex();
        gap3.leftMargin = dp(8);
        logActions2.addView(clearLogs, gap3);
        content.addView(logActions2, matchWrapWithTop(dp(8)));

        Button clear = secondaryButton("Limpar somente histórico de ocorrências");
        clear.setOnClickListener(v -> confirmClear());
        content.addView(clear, matchWrapWithTop(dp(8)));

        TextView privacy = text("Privacidade: o app trabalha com notificações no próprio aparelho. Não pede senha, QR Code nem login do WhatsApp e não possui permissão de Internet.", 12, Typeface.NORMAL, MUTED);
        privacy.setPadding(0, dp(22), 0, 0);
        content.addView(privacy);

        setContentView(scroll);
    }

    private void renderStatusCard() {
        LinearLayout box = verticalCard();
        TextView label = text("STATUS DO MONITOR", 12, Typeface.BOLD, MUTED);
        box.addView(label);

        boolean enabled = ListenerHealth.accessGranted(this);
        boolean componentEnabled = ListenerHealth.componentEnabled(this);
        boolean connected = NotificationMonitorService.isConnectedNow();
        String statusText;
        int statusColor;
        if (!componentEnabled) {
            statusText = "● Serviço desabilitado";
            statusColor = DANGER;
        } else if (!enabled) {
            statusText = "● Acesso às notificações NÃO autorizado";
            statusColor = DANGER;
        } else if (connected) {
            statusText = "● Monitor CONECTADO";
            statusColor = BRAND;
        } else {
            statusText = "● Autorizado, mas DESCONECTADO";
            statusColor = WARN;
        }
        TextView status = text(statusText, 17, Typeface.BOLD, statusColor);
        status.setPadding(0, dp(8), 0, dp(8));
        box.addView(status);

        String health = "Permissão do listener: " + (enabled ? "SIM" : "NÃO") +
                "\nComponente do serviço: " + (componentEnabled ? "HABILITADO" : "DESABILITADO") +
                "\nServiço conectado: " + (connected ? "SIM" : "NÃO") +
                "\nWatchdog em segundo plano: " + (MonitorWatchdogService.isRunning() ? "ATIVO" : "PARADO") +
                "\nNotificações do app: " + (ListenerHealth.appNotificationsEnabled(this) ? "SIM" : "NÃO");
        TextView healthText = text(health, 12, Typeface.BOLD, DARK);
        healthText.setTextIsSelectable(true);
        box.addView(healthText);

        TextView help = text(enabled
                        ? (connected
                            ? "V5: conexão confirmada. O watchdog mantém uma notificação silenciosa de “Monitor ativo” e tenta recuperar o listener automaticamente se ele cair."
                            : "O Android reconhece a autorização, mas o serviço ainda não conectou. Use ‘Reativar monitor’. Se continuar desconectado, o app vai orientar a desligar e ligar novamente o acesso às notificações.")
                        : "Esta instalação precisa receber novamente o Acesso às notificações. Sem isso o app não enxerga nenhuma mensagem do WhatsApp.",
                13, Typeface.NORMAL, DARK);
        help.setPadding(0, dp(8), 0, 0);
        box.addView(help);

        Button permission = enabled
                ? secondaryButton("Abrir acesso às notificações")
                : primaryButton("1. Conceder acesso às notificações");
        permission.setOnClickListener(v -> openNotificationAccess());
        box.addView(permission, matchWrapWithTop(dp(12)));

        if (enabled && !connected) {
            Button reactivate = primaryButton("2. Reativar monitor");
            reactivate.setOnClickListener(v -> reactivateMonitor());
            box.addView(reactivate, matchWrapWithTop(dp(8)));
        }

        Button appInfo = secondaryButton("Informações do app / configurações restritas");
        appInfo.setOnClickListener(v -> openAppDetails());
        box.addView(appInfo, matchWrapWithTop(dp(8)));

        Button battery = secondaryButton("Configurar execução em segundo plano");
        battery.setOnClickListener(v -> openBatterySettings());
        box.addView(battery, matchWrapWithTop(dp(8)));

        LinearLayout soundRow = horizontalRow();
        Button testSound = secondaryButton("🔊 Testar som");
        testSound.setOnClickListener(v -> AlertChannels.postTest(this));
        Button soundSettings = secondaryButton("Configurar som");
        soundSettings.setOnClickListener(v -> AlertChannels.openSettings(this));
        soundRow.addView(testSound, flex());
        LinearLayout.LayoutParams soundGap = flex();
        soundGap.leftMargin = dp(8);
        soundRow.addView(soundSettings, soundGap);
        box.addView(soundRow, matchWrapWithTop(dp(8)));

        content.addView(box, matchWrapWithBottom(dp(10)));
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
        LinearLayout.LayoutParams lp2 = flex();
        lp2.leftMargin = dp(8);
        row.addView(counterCard("7 dias", week), lp2);
        LinearLayout.LayoutParams lp3 = flex();
        lp3.leftMargin = dp(8);
        row.addView(counterCard("30 dias", month), lp3);
        content.addView(row, matchWrap());
    }

    private View counterCard(String label, int value) {
        LinearLayout box = verticalCard();
        box.setGravity(Gravity.CENTER);
        TextView n = text(String.valueOf(value), 24, Typeface.BOLD, DARK);
        TextView l = text(label, 12, Typeface.NORMAL, MUTED);
        box.addView(n);
        box.addView(l);
        return box;
    }

    private void renderRule(MonitorRule rule) {
        LinearLayout card = verticalCard();
        LinearLayout header = horizontalRow();
        TextView name = text(rule.name, 18, Typeface.BOLD, DARK);
        header.addView(name, flex());
        TextView state = text(rule.active ? "ATIVO" : "PAUSADO", 11, Typeface.BOLD, rule.active ? BRAND : MUTED);
        header.addView(state);
        card.addView(header);

        String groupText = rule.group.trim().isEmpty()
                ? "Grupo: não definido"
                : "Grupo: " + rule.group;
        TextView group = text(groupText, 13, Typeface.NORMAL, rule.group.trim().isEmpty() ? WARN : MUTED);
        group.setPadding(0, dp(6), 0, dp(3));
        card.addView(group);

        TextView words = text("Palavras: " + String.join(", ", rule.keywords), 13, Typeface.NORMAL, DARK);
        card.addView(words);

        String originalStatus;
        int originalColor;
        if (rule.group.trim().isEmpty()) {
            originalStatus = "WhatsApp original: não pode ser ocultado até definir o grupo";
            originalColor = WARN;
        } else if (rule.suppressOriginal) {
            originalStatus = "WhatsApp original: cancelar após captura";
            originalColor = BRAND;
        } else {
            originalStatus = "WhatsApp original: manter";
            originalColor = MUTED;
        }
        TextView suppress = text(originalStatus, 12, Typeface.BOLD, originalColor);
        suppress.setPadding(0, dp(7), 0, 0);
        card.addView(suppress);

        if (!rule.lastChannelId.isEmpty()) {
            String detected = "Canal capturado: " + rule.lastChannelId;
            if (!rule.lastConversationId.isEmpty()) detected += " • conversa identificada";
            TextView source = text(detected, 11, Typeface.NORMAL, MUTED);
            source.setPadding(0, dp(4), 0, 0);
            card.addView(source);

            Button silenceWhatsapp = secondaryButton("Configurar canal do WhatsApp (opcional)");
            silenceWhatsapp.setOnClickListener(v -> openWhatsAppChannelSettings(rule));
            card.addView(silenceWhatsapp, matchWrapWithTop(dp(9)));
        } else if (!rule.group.trim().isEmpty()) {
            TextView waiting = text("Envie 1 mensagem neste grupo. Depois da captura, o app mostrará aqui qual canal/conversa o WhatsApp usou para ajudar no diagnóstico.", 11, Typeface.NORMAL, MUTED);
            waiting.setPadding(0, dp(7), 0, 0);
            card.addView(waiting);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = horizontalRow();
        actions.setPadding(0, dp(12), 0, 0);

        Button edit = compactButton("Editar");
        edit.setOnClickListener(v -> showRuleDialog(rule));
        Button pause = compactButton(rule.active ? "Pausar" : "Ativar");
        pause.setOnClickListener(v -> {
            rule.active = !rule.active;
            store.upsertRule(rule);
            render();
        });
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

    private void renderOccurrences() {
        List<Occurrence> occurrences = store.getOccurrences();
        if (occurrences.isEmpty()) {
            TextView empty = text("Nenhuma ocorrência ainda. Depois de conceder a permissão, envie uma nova mensagem contendo “jaleco”, “farda” ou “uniforme” para testar.", 13, Typeface.NORMAL, MUTED);
            addCard(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        int limit = Math.min(10, occurrences.size());
        for (int i = 0; i < limit; i++) {
            Occurrence o = occurrences.get(i);
            LinearLayout card = verticalCard();
            TextView h = text(o.monitorName + "  •  " + fmt.format(new Date(o.timestamp)), 15, Typeface.BOLD, DARK);
            card.addView(h);

            String who = o.sender == null || o.sender.trim().isEmpty()
                    ? "Remetente não identificado"
                    : o.sender.trim();
            TextView sender = text(who, 13, Typeface.BOLD, DARK);
            sender.setPadding(0, dp(5), 0, 0);
            card.addView(sender);

            if (o.group != null && !o.group.isEmpty()) {
                TextView g = text(o.group, 12, Typeface.NORMAL, BRAND);
                g.setPadding(0, dp(2), 0, 0);
                card.addView(g);
            }
            TextView m = text(o.message == null || o.message.isEmpty()
                    ? "Mensagem sem texto disponível"
                    : o.message, 13, Typeface.NORMAL, DARK);
            m.setPadding(0, dp(7), 0, 0);
            card.addView(m);

            Button open = secondaryButton(o.directLinkCaptured
                    ? "Abrir conversa  →"
                    : "Abrir WhatsApp  →");
            open.setOnClickListener(v -> openOccurrence(o));
            card.addView(open, matchWrapWithTop(dp(10)));
            card.setOnClickListener(v -> openOccurrence(o));
            content.addView(card, matchWrapWithBottom(dp(8)));
        }
    }

    private void openOccurrence(Occurrence occurrence) {
        int result = NotificationActionRegistry.open(this, occurrence);
        DiagnosticLog.append(this, "OPEN_CONVERSATION", "occurrence=" + occurrence.id + " result=" + result);
        if (result == NotificationActionRegistry.OPEN_DIRECT) {
            Toast.makeText(this, "Abrindo a conversa capturada no WhatsApp.", Toast.LENGTH_SHORT).show();
        } else if (result == NotificationActionRegistry.OPEN_APP_FALLBACK) {
            Toast.makeText(this, "O atalho direto já não estava disponível. Abrindo o WhatsApp.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Não foi possível abrir o WhatsApp neste aparelho.", Toast.LENGTH_LONG).show();
        }
    }

    private void openWhatsAppChannelSettings(MonitorRule rule) {
        if (rule.lastChannelId == null || rule.lastChannelId.isEmpty()) {
            Toast.makeText(this, "Primeiro precisamos capturar uma notificação desse grupo.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String pkg = rule.lastSourcePackage == null || rule.lastSourcePackage.isEmpty()
                    ? "com.whatsapp"
                    : rule.lastSourcePackage;
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, rule.lastChannelId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && rule.lastConversationId != null
                    && !rule.lastConversationId.isEmpty()) {
                intent.putExtra(Settings.EXTRA_CONVERSATION_ID, rule.lastConversationId);
            }
            startActivity(intent);
            Toast.makeText(this, "Nesta tela, deixe a conversa/canal como SILENCIOSO. Não use ‘Silenciar’ dentro do WhatsApp.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não consegui abrir diretamente esse canal. Abra as notificações do WhatsApp nas Configurações do Android.", Toast.LENGTH_LONG).show();
        }
    }

    private void showRuleDialog(MonitorRule existing) {
        boolean editing = existing != null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);

        EditText name = field("Nome do monitoramento", editing ? existing.name : "");
        EditText group = field("Nome exato ou parte do nome do grupo", editing ? existing.group : "");
        EditText keywords = field("Palavras separadas por vírgula", editing ? String.join(", ", existing.keywords) : "");
        keywords.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        CheckBox active = new CheckBox(this);
        active.setText("Monitoramento ativo");
        active.setChecked(!editing || existing.active);

        CheckBox suppressOriginal = new CheckBox(this);
        suppressOriginal.setText("Ocultar notificação original do WhatsApp deste grupo");
        suppressOriginal.setChecked(editing ? existing.suppressOriginal : true);

        TextView suppressHelp = text("Importante: para cancelar a original, defina o grupo aqui e NÃO silencie o grupo dentro do WhatsApp. Na V3 o cancelamento é tentado pelo próprio listener depois da captura.", 11, Typeface.NORMAL, MUTED);
        suppressHelp.setPadding(dp(4), 0, dp(4), dp(4));

        form.addView(name);
        form.addView(group, matchWrapWithTop(dp(8)));
        form.addView(keywords, matchWrapWithTop(dp(8)));
        form.addView(active, matchWrapWithTop(dp(4)));
        form.addView(suppressOriginal, matchWrapWithTop(dp(2)));
        form.addView(suppressHelp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "Editar monitoramento" : "Novo monitoramento")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String g = group.getText().toString().trim();
            List<String> words = parseKeywords(keywords.getText().toString());
            if (n.isEmpty()) {
                name.setError("Informe um nome");
                return;
            }
            if (words.isEmpty()) {
                keywords.setError("Informe pelo menos uma palavra");
                return;
            }
            if (suppressOriginal.isChecked() && g.isEmpty()) {
                group.setError("Informe o grupo para ocultar apenas as notificações dele");
                return;
            }

            MonitorRule rule;
            if (editing) {
                rule = new MonitorRule(
                        existing.id,
                        n,
                        g,
                        words,
                        active.isChecked(),
                        suppressOriginal.isChecked(),
                        existing.lastSourcePackage,
                        existing.lastChannelId,
                        existing.lastConversationId,
                        existing.lastDetectedGroup,
                        existing.lastDetectedAt);
                if (!TextMatcher.groupMatches(existing.lastDetectedGroup, g)) {
                    rule.lastSourcePackage = "";
                    rule.lastChannelId = "";
                    rule.lastConversationId = "";
                    rule.lastDetectedGroup = "";
                    rule.lastDetectedAt = 0L;
                }
            } else {
                rule = MonitorRule.create(n, g, words);
                rule.active = active.isChecked();
                rule.suppressOriginal = suppressOriginal.isChecked();
            }
            store.upsertRule(rule);
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private List<String> parseKeywords(String raw) {
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) list.add(value);
        }
        return list;
    }

    private void confirmDelete(MonitorRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("Excluir monitoramento?")
                .setMessage("“" + rule.name + "” será removido. O histórico já registrado não será apagado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir", (d, w) -> {
                    store.deleteRule(rule.id);
                    render();
                }).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar histórico?")
                .setMessage("Somente as ocorrências visíveis serão apagadas. O listener, o log técnico e o controle de mensagens já processadas NÃO serão alterados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar", (d, w) -> {
                    store.clearOccurrences();
                    DiagnosticLog.append(this, "HISTORY_CLEARED", "somente ocorrências visíveis apagadas");
                    render();
                }).show();
    }

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar logs técnicos?")
                .setMessage("O histórico de ocorrências e o controle de mensagens processadas não serão apagados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpar logs", (d, w) -> {
                    DiagnosticLog.clear(this);
                    render();
                }).show();
    }

    private void maybeShowAccessGuidance() {
        if (isFinishing() || accessGuidanceShown || ListenerHealth.accessGranted(this)) return;
        accessGuidanceShown = true;
        new AlertDialog.Builder(this)
                .setTitle("Ative o monitor")
                .setMessage("A V0.7 ainda não recebeu o Acesso às notificações. Ative ‘Alerta de Assuntos’ nessa tela. Se o Android bloquear por ‘configurações restritas’, volte, abra ‘Informações do app’, toque nos três pontos e permita configurações restritas; depois retorne ao acesso às notificações.")
                .setPositiveButton("Abrir acesso", (d, w) -> openNotificationAccess())
                .setNeutralButton("Informações do app", (d, w) -> openAppDetails())
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void reactivateMonitor() {
        DiagnosticLog.append(this, "REACTIVATE_START", ListenerHealth.snapshot(this));
        if (!ListenerHealth.componentEnabled(this)) {
            Toast.makeText(this, "O componente do monitor está desabilitado. Abra as Informações do app e me envie o log se continuar assim.", Toast.LENGTH_LONG).show();
            render();
            return;
        }
        if (!ListenerHealth.accessGranted(this)) {
            Toast.makeText(this, "Primeiro conceda o Acesso às notificações.", Toast.LENGTH_LONG).show();
            openNotificationAccess();
            return;
        }
        if (NotificationMonitorService.isConnectedNow()) {
            Toast.makeText(this, "O monitor já está conectado.", Toast.LENGTH_SHORT).show();
            render();
            return;
        }

        boolean requested = NotificationMonitorService.requestHardReconnect(this, "botão Reativar monitor");
        Toast.makeText(this, requested ? "Reconexão solicitada. Vou conferir automaticamente." : "A reconexão não pôde ser solicitada agora. Veja o diagnóstico.", Toast.LENGTH_LONG).show();
        if (requested) scheduleReconnectVerification("botão Reativar monitor", true);
        render();
    }

    private void scheduleReconnectVerification(String reason, boolean showHelpIfFailed) {
        uiHandler.postDelayed(() -> {
            if (isFinishing()) return;
            boolean connected = NotificationMonitorService.isConnectedNow();
            DiagnosticLog.markReconnectCheck(this, connected, reason);
            render();
            if (connected) {
                reconnectHelpShown = false;
                Toast.makeText(this, "Monitor conectado ao Android.", Toast.LENGTH_LONG).show();
            } else if (showHelpIfFailed && !reconnectHelpShown) {
                reconnectHelpShown = true;
                showReconnectHelpDialog();
            }
        }, 2800L);
    }

    private void showReconnectHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Autorizado, mas não conectou")
                .setMessage("O Android informa que o acesso está autorizado, porém o serviço não recebeu onListenerConnected(). Abra o Acesso às notificações, DESATIVE ‘Alerta de Assuntos’, espere 2 segundos e ATIVE novamente. Depois volte para o app e toque em Atualizar.")
                .setPositiveButton("Abrir acesso", (d, w) -> openNotificationAccess())
                .setNeutralButton("Informações do app", (d, w) -> openAppDetails())
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            DiagnosticLog.error(this, "openAppDetails", e);
            Toast.makeText(this, "Abra Configurações > Apps > Alerta de Assuntos.", Toast.LENGTH_LONG).show();
        }
    }

    private void shareDiagnosticLog() {
        try {
            File file = DiagnosticLog.writeShareFile(this, NotificationMonitorService.isConnectedNow());
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Log Alerta de Assuntos V0.7");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartilhar log"));
        } catch (Exception e) {
            DiagnosticLog.error(this, "shareDiagnosticLog", e);
            Toast.makeText(this, "Falha ao compartilhar. Use ‘Copiar diagnóstico’ como alternativa.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyDiagnosticLog() {
        try {
            String payload = DiagnosticLog.exportText(this, NotificationMonitorService.isConnectedNow());
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard indisponível");
            clipboard.setPrimaryClip(ClipData.newPlainText("Log Alerta de Assuntos V0.7", payload));
            DiagnosticLog.append(this, "LOG_COPIED", "chars=" + payload.length());
            Toast.makeText(this, "Diagnóstico copiado. Você pode colar direto na conversa.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            DiagnosticLog.error(this, "copyDiagnosticLog", e);
            Toast.makeText(this, "Não consegui copiar o diagnóstico.", Toast.LENGTH_LONG).show();
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            DiagnosticLog.append(this, "OPEN_BATTERY_SETTINGS", "tela geral de otimização aberta");
        } catch (Exception e) {
            DiagnosticLog.error(this, "openBatterySettings", e);
            openAppDetails();
        }
    }

    private void exportDiagnosticLog() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            String fileName = "Alerta_Assuntos_V5_Log_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            startActivityForResult(intent, REQ_EXPORT_LOG);
        } catch (Exception e) {
            DiagnosticLog.error(this, "exportDiagnosticLog", e);
            Toast.makeText(this, "Não consegui abrir o seletor para salvar o log.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_LOG || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("OutputStream indisponível");
            String payload = DiagnosticLog.exportText(this, NotificationMonitorService.isConnectedNow());
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
            DiagnosticLog.append(this, "LOG_EXPORTED", "arquivo salvo pelo usuário");
            Toast.makeText(this, "Log salvo. Agora você pode anexar esse arquivo na conversa comigo.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            DiagnosticLog.error(this, "writeExportedLog", e);
            Toast.makeText(this, "Falha ao salvar o log.", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Abra Configurações > Notificações > Acesso às notificações.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isNotificationAccessEnabled() {
        return ListenerHealth.accessGranted(this);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
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
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = top;
        return p;
    }

    private LinearLayout.LayoutParams matchWrapWithBottom(int bottom) {
        LinearLayout.LayoutParams p = matchWrap();
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams wrapWithLeft(int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = left;
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
