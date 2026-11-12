package dev.nosurprise.integration.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Перехват логов старта: позволяет smoke-тесту утверждать, что в логах нет
 * фатальных ошибок запуска. {@link #install()} вызывается из
 * {@code @DynamicPropertySource} — до создания Spring context.
 */
public final class StartupLogCapture {

    private static final ListAppender<ILoggingEvent> APPENDER = new ListAppender<>();

    private StartupLogCapture() {
    }

    public static synchronized void install() {
        if (APPENDER.isStarted()) {
            return;
        }
        APPENDER.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(APPENDER);
    }

    /** Все события уровня ERROR, включая тип исключения. */
    public static List<String> errorMessages() {
        return APPENDER.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                .map(StartupLogCapture::describe)
                .toList();
    }

    private static String describe(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) {
            message += " | " + event.getThrowableProxy().getClassName() + ": "
                    + event.getThrowableProxy().getMessage();
        }
        return message;
    }
}
