package ir.layercloud.cortexdrone.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskIntentClassifierTest {
    @Test
    public void inactiveTaskStartsNewSession() {
        assertEquals(TaskIntent.NEW_TASK, TaskIntentClassifier.classify("mine 8 iron ore", false));
        assertEquals(TaskIntent.NEW_TASK, TaskIntentClassifier.classify("build me a small shelter", false));
    }

    @Test
    public void activeQuestionsDoNotReplaceTask() {
        assertEquals(TaskIntent.QUESTION, TaskIntentClassifier.classify("what are you doing?", true));
        assertEquals(TaskIntent.QUESTION, TaskIntentClassifier.classify("status", true));
        assertEquals(TaskIntent.QUESTION, TaskIntentClassifier.classify("how many did you mine", true));
        assertEquals(TaskIntent.QUESTION, TaskIntentClassifier.classify("any update", true));
    }

    @Test
    public void activeCancellationStopsTask() {
        assertEquals(TaskIntent.CANCEL, TaskIntentClassifier.classify("stop", true));
        assertEquals(TaskIntent.CANCEL, TaskIntentClassifier.classify("cancel the task", true));
        assertEquals(TaskIntent.CANCEL, TaskIntentClassifier.classify("please stop", true));
        assertEquals(TaskIntent.CANCEL, TaskIntentClassifier.classify("never mind", true));
        assertEquals(TaskIntent.CANCEL, TaskIntentClassifier.classify("abort", false));
    }

    @Test
    public void sideCommentsBecomeNotesNotQueuedTasks() {
        assertEquals(TaskIntent.BY_THE_WAY, TaskIntentClassifier.classify("by the way, there is a chest at the base", true));
        assertEquals(TaskIntent.BY_THE_WAY, TaskIntentClassifier.classify("btw bring a torch later", true));
        assertEquals(TaskIntent.BY_THE_WAY, TaskIntentClassifier.classify("also watch out for lava", true));
    }

    @Test
    public void activeImperativeMessagesModifyTask() {
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("mine 5 more iron", true));
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("actually change the target to coal", true));
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("can you dig down to y 20 instead", true));
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("place a torch there", true));
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("come back to me", true));
    }

    @Test
    public void politeCommandsAreNotQuestions() {
        assertTrue(TaskIntentClassifier.isPoliteCommand("can you mine 10 iron"));
        assertEquals(TaskIntent.NEW_TASK, TaskIntentClassifier.classify("can you mine 10 iron", false));
        assertEquals(TaskIntent.MODIFY, TaskIntentClassifier.classify("could you mine 10 iron", true));
    }

    @Test
    public void emptyMessageIsSafe() {
        assertEquals(TaskIntent.NEW_TASK, TaskIntentClassifier.classify("", false));
        assertEquals(TaskIntent.QUESTION, TaskIntentClassifier.classify(null, true));
    }
}
