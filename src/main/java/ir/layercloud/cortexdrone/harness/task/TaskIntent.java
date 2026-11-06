package ir.layercloud.cortexdrone.harness.task;

/**
 * How an incoming player message relates to the single active task.
 *
 * <p>The harness never queues instructions. While a task is running a new
 * message is one of: a question/status probe, a "by the way" note, a
 * modification of the running task, or a cancellation. When no task is
 * running the message starts a new task/session.</p>
 */
public enum TaskIntent {
    QUESTION,
    BY_THE_WAY,
    MODIFY,
    CANCEL,
    NEW_TASK
}
