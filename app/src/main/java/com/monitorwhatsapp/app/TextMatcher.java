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
        return normalize(groupHint).contains(normalize(configuredGroup));
    }
}
