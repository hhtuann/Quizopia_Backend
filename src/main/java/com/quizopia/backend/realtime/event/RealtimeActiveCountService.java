package com.quizopia.backend.realtime.event;

import com.quizopia.backend.attempt.domain.model.AttemptStatus;
import com.quizopia.backend.attempt.repository.AttemptRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dedicated bean for the post-commit active-count query. Extracted from {@link RealtimeEventBroadcaster}
 * so the {@code @Transactional(REQUIRES_NEW)} annotation is honored through the Spring proxy — a
 * self-invocation within the broadcaster would bypass the proxy and NOT open a fresh transaction.
 *
 * <p>The query is a single aggregate: {@code count attempts WHERE exam_session_id=? AND status='IN_PROGRESS'}.
 * No attempt rows are loaded. The new transaction reflects the committed state (start count includes
 * the new attempt; submit count excludes it).
 */
@Component
public class RealtimeActiveCountService {

    private final AttemptRepository attemptRepository;

    public RealtimeActiveCountService(AttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countActiveAttempts(Long sessionId) {
        return attemptRepository.countByExamSessionIdAndStatus(sessionId, AttemptStatus.IN_PROGRESS);
    }
}
