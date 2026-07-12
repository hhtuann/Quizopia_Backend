package com.quizopia.backend.realtime.support;

import com.quizopia.backend.attempt.repository.AttemptRepository;
import com.quizopia.backend.exam.repository.ExamSessionRepository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test-only {@link BeanPostProcessor} (B1R4-B2F1) that wraps the production
 * repository beans in a JDK
 * dynamic proxy. When the armed thread calls {@code findByIdForUpdate} on a
 * wrapped repository, the proxy
 * signals <b>BEFORE delegating to the real method</b> — right before the
 * {@code FOR UPDATE} SQL blocks.
 *
 * <p>
 * This is a TRUE repository-level intercept: the signal fires inside the
 * proxy's {@code invoke()},
 * not in the test worker before the service call. Combined with
 * {@code b.isDone()==false} while worker A
 * holds the outer transaction's lock, this proves worker B's thread reached the
 * exact repository lock
 * method and is blocked there.
 *
 * <p>
 * Lock points (exact production methods):
 * <table>
 * <tr>
 * <th>Enum</th>
 * <th>Repository</th>
 * <th>Method</th>
 * </tr>
 * <tr>
 * <td>{@link LockPoint#SESSION_FOR_UPDATE}</td>
 * <td>{@code ExamSessionRepository}</td>
 * <td>{@code findByIdForUpdate}</td>
 * </tr>
 * <tr>
 * <td>{@link LockPoint#ATTEMPT_FOR_UPDATE}</td>
 * <td>{@code AttemptRepository}</td>
 * <td>{@code findByIdForUpdate}</td>
 * </tr>
 * </table>
 *
 * <p>
 * Usage:
 * 
 * <pre>{@code
 * // Worker B:
 * probe.armCurrentThread(LockPoint.ATTEMPT_FOR_UPDATE);
 * service.submitAttempt(...);  // blocks inside AttemptRepository.findByIdForUpdate
 *
 * // Main:
 * assertThat(probe.awaitEntered(LockPoint.ATTEMPT_FOR_UPDATE, 10)).isTrue();
 * assertThat(workerB.isDone()).isFalse();
 * releaseA.countDown();
 * }</pre>
 */
public class RepositoryLockEntryProbe implements BeanPostProcessor {

    public enum LockPoint {
        SESSION_FOR_UPDATE, // ExamSessionRepository.findByIdForUpdate
        ATTEMPT_FOR_UPDATE // AttemptRepository.findByIdForUpdate
    }

    private volatile Thread targetThread;
    private volatile CompletableFuture<LockPoint> enteredSignal = new CompletableFuture<>();

    // --- probe API ---

    /** Arms the probe for the current thread, expecting the given lock point. */
    public void armCurrentThread(LockPoint expected) {
        this.targetThread = Thread.currentThread();
    }

    /** Clears the probe (called by @BeforeEach). Creates a fresh signal future. */
    public void reset() {
        this.targetThread = null;
        this.enteredSignal = new CompletableFuture<>();
    }

    /**
     * Blocks until the armed thread signals entry at the expected lock point;
     * returns false on timeout.
     */
    public boolean awaitEntered(LockPoint expected, long timeoutSeconds) {
        try {
            LockPoint actual = enteredSignal.get(timeoutSeconds, TimeUnit.SECONDS);
            return actual == expected;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            throw new AssertionError("probe await failed", e);
        }
    }

    // --- internal signal (called from the repository proxy, NOT from the test
    // worker) ---

    private void signalFromProxy(LockPoint actualLockPoint) {
        if (Thread.currentThread() == targetThread && enteredSignal != null) {
            enteredSignal.complete(actualLockPoint);
        }
    }

    // --- BeanPostProcessor: wrap repository beans ---

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ExamSessionRepository repo && !isAlreadyWrapped(bean)) {
            return wrapRepository(repo, "findByIdForUpdate", LockPoint.SESSION_FOR_UPDATE);
        }
        if (bean instanceof AttemptRepository repo && !isAlreadyWrapped(bean)) {
            return wrapRepository(repo, "findByIdForUpdate", LockPoint.ATTEMPT_FOR_UPDATE);
        }
        return bean;
    }

    private static boolean isAlreadyWrapped(Object bean) {
        if (!Proxy.isProxyClass(bean.getClass()))
            return false;
        try {
            return Proxy.getInvocationHandler(bean) instanceof LockEntryInvocationHandler;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T wrapRepository(T target, String lockMethodName, LockPoint lockPoint) {
        return (T) Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                target.getClass().getInterfaces(),
                new LockEntryInvocationHandler(target, lockMethodName, lockPoint, this));
    }

    /**
     * Invocation handler that signals the probe before the lock method, then
     * delegates.
     */
    private static final class LockEntryInvocationHandler implements InvocationHandler {
        private final Object target;
        private final String lockMethodName;
        private final LockPoint lockPoint;
        private final RepositoryLockEntryProbe probe;

        LockEntryInvocationHandler(Object target, String lockMethodName, LockPoint lockPoint,
                RepositoryLockEntryProbe probe) {
            this.target = target;
            this.lockMethodName = lockMethodName;
            this.lockPoint = lockPoint;
            this.probe = probe;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals(lockMethodName)) {
                probe.signalFromProxy(lockPoint); // fires INSIDE the proxy, before the real method blocks
            }
            return method.invoke(target, args);
        }
    }
}
