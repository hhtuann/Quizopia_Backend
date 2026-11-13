package com.quizopia.backend.monitoring.application;

import com.quizopia.backend.attempt.application.AttemptAuthorizationService;
import com.quizopia.backend.attempt.domain.model.Attempt;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.monitoring.domain.model.ActivityEventSource;
import com.quizopia.backend.monitoring.domain.model.ActivityEventType;
import com.quizopia.backend.monitoring.domain.model.AttemptActivityEvent;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse;
import com.quizopia.backend.monitoring.dto.AttemptTimelineResponse.TimelineItem;
import com.quizopia.backend.monitoring.exception.MonitoringErrorCode;
import com.quizopia.backend.monitoring.exception.MonitoringException;
import com.quizopia.backend.monitoring.repository.AttemptActivityEventRepository;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationException;
import com.quizopia.backend.realtime.authorization.RealtimeAuthorizationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cursor-paged durable Attempt timeline (P4-A4, issue #83).
 *
 * <p><b>Authorization (IDOR-safe):</b> the attempt OWNER (learner holding
 * ATTEMPT_ACTIVITY_READ) or the SESSION OWNER teacher (holding
 * EXAM_SESSION_MONITOR — the exact gate of the STOMP teacher topic, reused
 * via {@link RealtimeAuthorizationService}). Everyone else gets 403/404 with
 * no existence leak.
 *
 * <p><b>Stable ordering:</b> (server_received_at, id) ascending keyset
 * pagination. The cursor is opaque — base64 of
 * {@code "<epochMicros>:<id>"} of the LAST item of the previous page,
 * microsecond-precise. Late-arriving events (client_occurred_at in the past
 * but received later) only ever land in later pages; already-served pages
 * are immutable, so repeated page requests never duplicate or lose events.
 * Duplicate event ids cannot exist (UNIQUE event_id).
 *
 * <p>No answer content is ever returned: ANSWER_ACCEPTED metadata carries
 * only {attemptQuestionId, acceptedSequence}.
 */
@Service
public class AttemptTimelineService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_PAGE_SIZE = 50;

    private final AttemptAuthorizationService auth;
    private final AttemptRepository attemptRepo;
    private final AttemptActivityEventRepository eventRepo;
    private final RealtimeAuthorizationService realtimeAuthz;
    private final Clock clock;

    public AttemptTimelineService(AttemptAuthorizationService auth,
            AttemptRepository attemptRepo,
            AttemptActivityEventRepository eventRepo,
            RealtimeAuthorizationService realtimeAuthz,
            Clock clock) {
        this.auth = auth;
        this.attemptRepo = attemptRepo;
        this.eventRepo = eventRepo;
        this.realtimeAuthz = realtimeAuthz;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttemptTimelineResponse timeline(Long userId, Long attemptId, String cursor,
            int limit, String types, String source, Instant from, Instant to) {
        Attempt attempt = authorizeRead(userId, attemptId);

        int pageSize = normalizeLimit(limit);
        ActivityEventSource sourceFilter = parseSource(source);
        Set<ActivityEventType> typeFilter = parseTypes(types);
        CursorPosition position = CursorPosition.decode(cursor);


        // Typed Specification (NOT JPQL null binds — Hibernate 7 + PostgreSQL
        // cannot type an unbound null Instant; see repository javadoc).
        var spec = timelineSpecification(attemptId, position, from, to, sourceFilter, typeFilter);
        var sort = org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("serverReceivedAt"),
                org.springframework.data.domain.Sort.Order.asc("id"));
        // Fetch one extra row to detect whether another page follows.
        List<AttemptActivityEvent> page = eventRepo.findAll(spec,
                org.springframework.data.domain.PageRequest.of(0, pageSize + 1, sort)).getContent();

        boolean hasNext = page.size() > pageSize;
        List<AttemptActivityEvent> items = hasNext ? page.subList(0, pageSize) : page;

        String nextCursor = null;
        if (hasNext && !items.isEmpty()) {
            AttemptActivityEvent last = items.get(items.size() - 1);
            nextCursor = CursorPosition.encode(last.getServerReceivedAt(), last.getId());
        }

        return new AttemptTimelineResponse(
                attemptId,
                items.stream()
                        .map(e -> new TimelineItem(
                                e.getEventId(),
                                e.getEventType().name(),
                                e.getSource().name(),
                                e.getClientOccurredAt(),
                                e.getClientSequence(),
                                e.getServerReceivedAt(),
                                e.getMetadata()))
                        .toList(),
                nextCursor,
                Instant.now(clock));
    }

    private org.springframework.data.jpa.domain.Specification<AttemptActivityEvent> timelineSpecification(
            Long attemptId, CursorPosition position, Instant from, Instant to,
            ActivityEventSource sourceFilter, Set<ActivityEventType> typeFilter) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            predicates.add(cb.equal(root.get("attemptId"), attemptId));
            if (position != null) {
                // Keyset cursor: strictly after (serverReceivedAt, id).
                predicates.add(cb.or(
                        cb.greaterThan(root.get("serverReceivedAt"), position.serverReceivedAt()),
                        cb.and(
                                cb.equal(root.get("serverReceivedAt"), position.serverReceivedAt()),
                                cb.greaterThan(root.get("id"), position.id()))));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("serverReceivedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("serverReceivedAt"), to));
            }
            if (sourceFilter != null) {
                predicates.add(cb.equal(root.get("source"), sourceFilter));
            }
            if (typeFilter != null && !typeFilter.isEmpty()) {
                predicates.add(root.get("eventType").in(typeFilter));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private Attempt authorizeRead(Long userId, Long attemptId) {
        Attempt attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> new MonitoringException(MonitoringErrorCode.MONITORING_ATTEMPT_NOT_FOUND));
        if (attempt.getUserId().equals(userId)) {
            // Owner path (learner or teacher-as-learner on own attempt).
            auth.requireLearnerWithPermission(userId, "ATTEMPT_ACTIVITY_READ");
            return attempt;
        }
        // Teacher path: session owner + EXAM_SESSION_MONITOR — the same gate
        // as the STOMP teacher topic (authorizeTeacherTopic checks BOTH).
        // Generic denial, no existence leak.
        try {
            realtimeAuthz.authorizeTeacherTopic(userId, attempt.getExamSessionId());
        } catch (RealtimeAuthorizationException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_ACCESS_DENIED);
        }
        return attempt;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private ActivityEventSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return ActivityEventSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                    "source must be CLIENT or SERVER");
        }
    }

    private Set<ActivityEventType> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return null;
        }
        Set<ActivityEventType> parsed = new LinkedHashSet<>();
        for (String raw : types.split(",")) {
            ActivityEventType type = ActivityEventType.parse(raw);
            if (type == null) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_VALIDATION_ERROR,
                        "unknown event type in types filter");
            }
            parsed.add(type);
        }
        return parsed;
    }

    /** Opaque keyset cursor: base64("<epochMicros>:<id>"), microsecond-precise. */
    record CursorPosition(Instant serverReceivedAt, Long id) {

        static String encode(Instant serverReceivedAt, Long id) {
            long micros = Math.addExact(
                    Math.multiplyExact(serverReceivedAt.getEpochSecond(), 1_000_000L),
                    serverReceivedAt.getNano() / 1_000L);
            String raw = micros + ":" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
        }

        static CursorPosition decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(
                        Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.US_ASCII);
                int sep = raw.indexOf(':');
                if (sep <= 0) {
                    throw new IllegalArgumentException("missing separator");
                }
                long micros = Long.parseLong(raw.substring(0, sep));
                return new CursorPosition(
                        Instant.ofEpochSecond(
                                Math.floorDiv(micros, 1_000_000L),
                                Math.floorMod(micros, 1_000_000L) * 1_000L),
                        Long.parseLong(raw.substring(sep + 1)));
            } catch (RuntimeException e) {
                throw new MonitoringException(MonitoringErrorCode.MONITORING_CURSOR_INVALID);
            }
        }
    }
}
