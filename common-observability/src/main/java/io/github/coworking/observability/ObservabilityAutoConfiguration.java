package io.github.coworking.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps traces to what a user did. Without these rules most traces in Jaeger would be Prometheus
 * scraping {@code /actuator/prometheus} every few seconds and healthchecks querying the database.
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    private static final String ACTUATOR_PREFIX = "/actuator";

    /**
     * A JDBC span with no parent was not caused by a request: the database health indicator,
     * Flyway at startup, the connection pool. As a trace of its own it is noise.
     */
    @Bean
    ObservationPredicate skipJdbcOutsideRequests() {
        return (name, context) -> !name.startsWith("jdbc") || context.getParentObservation() != null;
    }

    // Both web stacks name their context class ServerRequestObservationContext, hence the qualified names.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class Servlet {

        @Bean
        ObservationPredicate skipActuatorRequests() {
            return (name, context) -> !(context instanceof org.springframework.http.server.observation.ServerRequestObservationContext request
                    && request.getCarrier().getRequestURI().startsWith(ACTUATOR_PREFIX));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.REACTIVE)
    static class Reactive {

        @Bean
        ObservationPredicate skipActuatorRequests() {
            return (name, context) -> !(context instanceof org.springframework.http.server.reactive.observation.ServerRequestObservationContext request
                    && request.getCarrier().getPath().value().startsWith(ACTUATOR_PREFIX));
        }
    }
}
