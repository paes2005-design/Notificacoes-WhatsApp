package com.monitorwhatsapp.app;

import java.text.Normalizer;
import java.util.Locale;

public class TextMatcher {
    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized;
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

        // Mantém o comportamento original para um único valor e também protege
        // nomes reais que possuam vírgula: primeiro tentamos a expressão inteira.
        if (!configured.isEmpty() && haystack.contains(configured)) return true;

        // Permite listas separadas por vírgula. Isso é especialmente importante
        // para o filtro de remetentes, por exemplo:
        // "Osni Corintiano, Amauri - São Paulino, João Paulo - Zeus".
        // Basta qualquer um dos itens coincidir com o remetente recebido.
        if (configuredGroup.contains(",")) {
            String[] options = configuredGroup.split(",");
            for (String option : options) {
                String normalizedOption = normalize(option);
                if (!normalizedOption.isEmpty() && haystack.contains(normalizedOption)) {
                    return true;
                }
            }
        }

        return false;
    }
}
