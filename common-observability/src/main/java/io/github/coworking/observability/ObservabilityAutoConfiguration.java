package io.github.coworking.observability;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Keeps traces to what a user did. Sleuth already skips {@code /actuator} requests; what is left
 * is outgoing calls nobody's request caused.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * A client span with no parent was not caused by a request: the database health indicator,
     * Flyway at startup, the connection pool. As a trace of its own it is noise.
     * <p>
     * Ordered first, because Brave stops at the first handler that returns {@code false}, and the
     * Zipkin reporter is one of the handlers.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SpanHandler dropClientSpansOutsideRequests() {
        return new SpanHandler() {
            @Override
            public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                return !(span.kind() == brave.Span.Kind.CLIENT && context.parentIdAsLong() == 0L);
            }
        };
    }
}
