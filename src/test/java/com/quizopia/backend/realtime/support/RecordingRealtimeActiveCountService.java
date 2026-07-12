package com.quizopia.backend.realtime.support;

import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.realtime.event.RealtimeActiveCountService;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

/**
 * Test-only decorator (B1R4-B §12/§13/§14, B1R4-B1F §4) over {@link RealtimeActiveCountService}, installed
 * as a {@code @Primary} bean by {@link RealtimeTestSupportConfig}. It:
 * <ul>
 *   <li>counts {@code countActiveAttempts} invocations;</li>
 *   <li>records the {@link TransactionSynchronizationManager} state at invocation — active/readOnly/name +
 *       the <b>resource identity</b> ({@code System.identityHashCode} of the bound {@code ConnectionHolder})
 *       so the test can prove the count tx's resource differs from the write tx's resource;</li>
 *   <li>can fail one-shot on a sessionId predicate.</li>
 * </ul>
 */
public class RecordingRealtimeActiveCountService extends RealtimeActiveCountService {

    /** Captured transaction context at the moment the REQUIRES_NEW count tx is live. */
    public record TxContext(boolean active, boolean readOnly, String name, int resourceIdentity, String resourceClass) {}

    private final AtomicInteger invocationCount = new AtomicInteger();
    private final List<TxContext> recordedContexts = new CopyOnWriteArrayList<>();
    private volatile LongPredicate failPredicate;
    private DataSource dataSource;

    public RecordingRealtimeActiveCountService(AttemptRepository attemptRepository) {
        super(attemptRepository);
    }

    /** Binds the DataSource used to look up the bound ConnectionHolder (the same key Spring's tx manager uses). */
    public void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public List<TxContext> recordedContexts() {
        return List.copyOf(recordedContexts);
    }

    /** Configure a one-shot failure for the next {@code countActiveAttempts(sessionId)} matching. */
    public void failNextCount(LongPredicate predicate) {
        this.failPredicate = predicate;
    }

    public void reset() {
        invocationCount.set(0);
        recordedContexts.clear();
        failPredicate = null;
    }

    @Override
    public long countActiveAttempts(Long sessionId) {
        invocationCount.incrementAndGet();
        Object resource = dataSource != null ? TransactionSynchronizationManager.getResource(dataSource) : null;
        recordedContexts.add(new TxContext(
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                resource != null ? System.identityHashCode(resource) : 0,
                resource != null ? resource.getClass().getName() : null));
        LongPredicate p = failPredicate;
        if (p != null && p.test(sessionId)) {
            failPredicate = null; // one-shot
            throw new RuntimeException("test-injected active-count query failure for session " + sessionId);
        }
        return super.countActiveAttempts(sessionId);
    }
}
