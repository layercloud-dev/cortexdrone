package ir.layercloud.cortexdrone.harness.agent;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/** Chat line shaping rules. */
public class ChatLinesTest {
    @Test
    public void shortMessagesStayOnOneLine() {
        List<String> lines = ChatLines.split("hello there");
        assertEquals(1, lines.size());
        assertEquals("hello there", lines.get(0));
    }

    @Test
    public void longMessagesAreSplitWithoutBreakingWords() {
        StringBuilder text = new StringBuilder();
        while (text.length() < 700) text.append("word ");
        List<String> lines = ChatLines.split(text.toString().trim());
        assertTrue("a 700-char message needs several lines", lines.size() > 1);
        for (String line : lines) {
            assertTrue("line too long: " + line.length(), line.length() <= ChatLines.MAX_LINE);
            assertFalse("a split must not leave a leading space", line.startsWith(" "));
            assertFalse("a split must not leave a trailing space", line.endsWith(" "));
        }
    }

    @Test
    public void emptyInputProducesNoLines() {
        assertTrue(ChatLines.split(null).isEmpty());
        assertTrue(ChatLines.split("").isEmpty());
    }

    @Test
    public void limitCollapsesWhitespaceAndTruncates() {
        assertEquals("a b c", ChatLines.limit("  a\n\nb   c  ", 20));
        String limited = ChatLines.limit("abcdefghij", 5);
        assertEquals("ab...", limited);
        assertEquals(5, limited.length());
    }

    @Test
    public void skillNamesAreStableAndBounded() {
        assertEquals("mine_iron_ore", ChatLines.skillName("Mine iron ore!"));
        assertTrue(ChatLines.skillName("   ").startsWith("workflow_"));
        assertTrue(ChatLines.skillName(null).length() > 0);
        assertTrue(ChatLines.skillName("one two three four five six seven").length() <= 48);
    }

    @Test
    public void nonLatinPromptsStillGetDistinctNames() {
        String first = ChatLines.skillName("سلام دنیا");
        String second = ChatLines.skillName("یک کار دیگر");
        assertNotEquals("distinct prompts must not collide", first, second);
    }
}
