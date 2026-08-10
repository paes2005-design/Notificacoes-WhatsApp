package com.monitorwhatsapp.app;

import java.text.Normalizer;
import java.util.Locale;

public class TextMatcher {
    public static String normalize(String value) {
        if (value == null) return "";

        String cleaned = value
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replaceAll("[\\p{Cf}\\u200B-\\u200D\\uFEFF]", " ");

        return Normalizer.normalize(cleaned, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean containsKeyword(String haystack, String keyword) {
        String text = normalize(haystack);
        String word = normalize(keyword);
        if (word.isEmpty()) return false;
        return text.contains(word);
    }

    public static boolean groupMatches(String groupHint, String configuredGroup) {
        if (configuredGroup == null || configuredGroup.trim().isEmpty()) return true;

        String haystack = normalize(groupHint);
        String configured = normalize(configuredGroup);
        boolean compositeHaystack = groupHint != null && groupHint.contains(" | ");

        // O serviço usa este matcher em dois cenários:
        // 1) grupo: um haystack composto com título/subtexto/conversa -> busca por trecho;
        // 2) remetente/conversa direta: um único nome -> comparação exata normalizada.
        if (matchesValue(haystack, configured, compositeHaystack)) return true;

        // Listas de remetentes podem ser separadas por vírgula, ponto e vírgula ou quebra de linha.
        // A tentativa da expressão inteira acima preserva nomes reais que contenham vírgula.
        if (configuredGroup.contains(",") || configuredGroup.contains(";")
                || configuredGroup.contains("\n") || configuredGroup.contains("\r")) {
            String[] options = configuredGroup.split("[,;\\r\\n]+");
            for (String option : options) {
                String normalizedOption = normalize(option);
                if (matchesValue(haystack, normalizedOption, compositeHaystack)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesValue(String haystack, String configured, boolean partialMatch) {
        if (configured == null || configured.isEmpty()) return false;
        if (partialMatch) return haystack.contains(configured);

        // O WhatsApp pode exibir remetentes não salvos com um prefixo visual "~".
        // Removemos apenas esse prefixo inicial nas comparações diretas.
        return stripWhatsAppSenderPrefix(haystack).equals(stripWhatsAppSenderPrefix(configured));
    }

    private static String stripWhatsAppSenderPrefix(String value) {
        if (value == null) return "";
        return value.replaceFirst("^~\\s*", "").trim();
    }
}
