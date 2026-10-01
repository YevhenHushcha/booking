package io.github.coworking.observability;

import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Hands logging over to Log4j 1.x and its {@code log4j.properties}.
 * <p>
 * Boot 2 has no Log4j 1 support. Left alone, it finds no Logback and no Log4j 2 and falls back to
 * java.util.logging: the app still logs through Log4j, but every {@code logging.level.*} property
 * is applied to JUL loggers nobody reads, and silently does nothing. Turning Boot's logging system
 * off makes that explicit: levels and patterns live in {@code log4j.properties} only.
 * <p>
 * Registered in {@code spring.factories} rather than as a bean: it has to run on the first event,
 * before the application context, and before Boot's own {@code LoggingApplicationListener} picks
 * a logging system.
 */
public class Log4jLoggingListener implements ApplicationListener<ApplicationStartingEvent>, Ordered {

    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
        // Boot would install the JUL -> SLF4J bridge itself; with its logging system off, nobody does.
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
