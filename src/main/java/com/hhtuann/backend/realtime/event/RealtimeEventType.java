package com.hhtuann.backend.realtime.event;

/**
 * The six canonical Day-7 realtime event types (frozen contract §9.6). No aliases — there is no
 * {@code PARTICIPANT_STARTED}/{@code ANSWER_SAVED}/{@code ATTEMPT_RESUMED}/{@code ATTEMPT_GRADED}
 * in Day 7.
 *
 * <ul>
 *   <li>{@link #SESSION_OPENED} / {@link #SESSION_CLOSED} → {@code /topic/exam-sessions/{sessionId}}.</li>
 *   <li>{@link #ATTEMPT_STARTED} / {@link #ATTEMPT_SUBMITTED} → {@code /topic/exam-sessions/{sessionId}}.</li>
 *   <li>{@link #ACTIVE_COUNT_CHANGED} → {@code /topic/exam-sessions/{sessionId}}.</li>
 *   <li>{@link #SERVER_TIME_SYNC} → {@code /user/queue/attempt} (after personal subscription).</li>
 * </ul>
 */
public enum RealtimeEventType {
    SESSION_OPENED,
    SESSION_CLOSED,
    ATTEMPT_STARTED,
    ATTEMPT_SUBMITTED,
    ACTIVE_COUNT_CHANGED,
    SERVER_TIME_SYNC
}
