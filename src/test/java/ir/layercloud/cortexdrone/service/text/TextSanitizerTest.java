package ir.layercloud.cortexdrone.service.text;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Untrusted text must be safe to render in 1.7.10 chat, the drone status line
 * and the line-based debug log without losing legitimate Unicode (including
 * Persian, which relies on ZWNJ).
 */
public class TextSanitizerTest {
    @Test
    public void sectionSignFormattingIsRemoved() {
        assertEquals("Red and Bold", TextSanitizer.chat("\u00A7cRed\u00A7r and \u00A7lBold"));
    }

    @Test
    public void controlCharactersAreRemoved() {
        assertEquals("abcd", TextSanitizer.chat("a\u0000b\u0007c\u001Fd\u007F"));
    }

    @Test
    public void chatCollapsesLineBreaksAndTabs() {
        assertEquals("a b c", TextSanitizer.chat("  a\n\tb \r\n  c  "));
    }

    @Test
    public void dialogKeepsParagraphsButNotWhitespaceRuns() {
        assertEquals("line one\n\nline two",
                TextSanitizer.dialog("line one\n\n\n\nline two"));
        assertEquals("a b", TextSanitizer.dialog("a\tb"));
    }

    @Test
    public void nonBmpGlyphsAreRemovedWhole() {
        assertEquals("hi there", TextSanitizer.chat("hi \uD83D\uDE00 there"));
        assertEquals("ab", TextSanitizer.chat("a\uD83Db"));
    }

    @Test
    public void persianTextAndZwnjSurvive() {
        String persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645 \u0628\u0631\u0648\u0645";
        assertEquals(persian, TextSanitizer.chat(persian));
        assertTrue(TextSanitizer.chat(persian).indexOf('\u200C') >= 0);
    }

    @Test
    public void bidiAndZeroWidthControlsAreRemoved() {
        assertEquals("abcd", TextSanitizer.chat("a\u200Bb\u202Ec\uFEFFd"));
        // ZWNJ/ZWJ are orthographic, not invisible noise, so they stay.
        assertEquals("a\u200Db\u200Cc", TextSanitizer.chat("a\u200Db\u200Cc"));
    }

    @Test
    public void truncationHappensAfterSanitizing() {
        assertEquals("Red", TextSanitizer.chat("\u00A7cRed\u00A7r", 3));
        assertEquals("", TextSanitizer.chat(null, 10));
        assertEquals("", TextSanitizer.dialog(null));
    }

    @Test
    public void logIsAlwaysOneLine() {
        assertEquals("a b", TextSanitizer.log("a\nb"));
        assertEquals("done", TextSanitizer.log("done\u00A7r"));
    }
}
