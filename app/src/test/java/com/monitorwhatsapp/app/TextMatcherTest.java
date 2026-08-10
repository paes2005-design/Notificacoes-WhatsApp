package com.monitorwhatsapp.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TextMatcherTest {
    private static final String SENDERS =
            "Osni corintiano, Amauri - São Paulino, João Paulo - zeus, Adriane Piedade";

    @Test
    public void matchesConfiguredSendersIgnoringCaseAndAccents() {
        assertTrue(TextMatcher.groupMatches("Osni Corintiano", SENDERS));
        assertTrue(TextMatcher.groupMatches("Amauri - São Paulino", SENDERS));
        assertTrue(TextMatcher.groupMatches("João Paulo - Zeus", SENDERS));
    }

    @Test
    public void matchesWhatsAppTildeAndSpecialWhitespace() {
        assertTrue(TextMatcher.groupMatches("~\u202FAdriane Piedade", SENDERS));
    }

    @Test
    public void matchesNamesContainingZeroWidthCharacters() {
        assertTrue(TextMatcher.groupMatches("Adriane\u200BPiedade", SENDERS));
    }

    @Test
    public void doesNotAcceptTyposOrPartialNames() {
        assertFalse(TextMatcher.groupMatches("João Paulo - Zeus", "João Paulo - zeua"));
        assertFalse(TextMatcher.groupMatches("Mariana", "Ana"));
    }

    @Test
    public void keepsPartialMatchingForCompositeGroupHaystack() {
        assertTrue(TextMatcher.groupMatches(
                "BOLEIROS ⚽🏆 (3 mensagens) | BOLEIROS ⚽🏆: Osni",
                "BOLEIROS ⚽🏆"));
    }

    @Test
    public void normalizesKeywordsAsBefore() {
        assertTrue(TextMatcher.containsKeyword("Fárda chegou", "farda"));
    }
}
