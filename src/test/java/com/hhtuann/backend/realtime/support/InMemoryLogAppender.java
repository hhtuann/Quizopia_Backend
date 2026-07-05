package com.hhtuann.backend.realtime.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only in-memory Logback appender (B1R4-B §17). Attach to a logger, run the action, then assert
 * the captured (formatted) messages omit a sensitive marker. AutoCloseable detaches on close so it
 * never leaks across tests. Deterministic — no reliance on console capture.
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final Logger logger;

    private InMemoryLogAppender(Logger logger) {
        this.logger = logger;
    }

    /** Attaches a fresh appender to the named logger (e.g. the broadcaster's logger). */
    public static InMemoryLogAppender attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        InMemoryLogAppender appender = new InMemoryLogAppender(logger);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        events.add(eventObject);
    }

    /** All captured formatted messages (level + throwable-rendered where present). */
    public List<String> formattedMessages() {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Joined captured text (formatted messages only; throwable info is appended by Logback's layout
     *  to the formatted message when a throwable is passed). */
    public String joined() {
        return String.join("\n", formattedMessages());
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
