# Notificações WhatsApp

Aplicativo Android para monitorar notificações do WhatsApp, filtrar assuntos importantes e gerar alertas personalizados.

## Versão Cliente

A versão cliente permite configurar:

- conversa/grupo monitorado;
- pessoa/remetente específico (opcional);
- palavras importantes (opcional);
- histórico dos alertas;
- abertura da conversa pelo PendingIntent fornecido pelo WhatsApp;
- monitoramento em segundo plano por NotificationListenerService.

O aplicativo trabalha somente com notificações que o Android entrega após o usuário conceder explicitamente o acesso às notificações. Não utiliza login do WhatsApp, QR Code, banco privado do WhatsApp nem Accessibility Service.

> Projeto em desenvolvimento. A chave incluída nas versões de teste é apenas para builds de desenvolvimento e não deve ser usada como assinatura de produção.
