package io.github.coworking.gateway.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Renders errors raised inside the gateway as RFC 9457 problem details, the format the services use.
 * <p>
 * Boot's default handler cannot be kept for two reasons: it answers 500 when the circuit breaker
 * reports an upstream 5xx (that exception is not a {@link ResponseStatusException}), and it has no
 * way to add {@code Retry-After} while the circuit is open.
 */
@Component
@Order(-2) // ahead of Boot's DefaultErrorWebExceptionHandler, which is registered at -1
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper objectMapper;
    private final Duration openStateWait;

    public GatewayErrorHandler(
            ObjectMapper objectMapper,
            @Value("${resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state}") Duration openStateWait) {
        this.objectMapper = objectMapper;
        this.openStateWait = openStateWait;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(error);
        }

        ProblemDetail problem = toProblem(error, routeId(exchange));
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        if (error instanceof ServiceUnavailableException) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(openStateWait.toSeconds()));
        }

        response.setStatusCode(HttpStatusCode.valueOf(problem.getStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(problem);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private static ProblemDetail toProblem(Throwable error, String route) {
        if (error instanceof ServiceUnavailableException) {
            // Thrown by the circuit breaker when it is open: the call was not even attempted.
            return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Circuit breaker for route '" + route + "' is open; the service is failing and calls are paused");
        }
        if (error instanceof HttpStatusCodeException upstream) {
            // An upstream 5xx that the circuit breaker counted as a failure. The upstream body is
            // not relayed: a 500 from a service may carry internals the client should not see.
            return ProblemDetail.forStatusAndDetail(upstream.getStatusCode(),
                    "Route '" + route + "' responded with " + upstream.getStatusCode().value());
        }
        if (error instanceof TimeoutException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                    "Route '" + route + "' did not answer in time");
        }
        if (error instanceof ResponseStatusException statusError) {
            return ProblemDetail.forStatusAndDetail(statusError.getStatusCode(),
                    statusError.getReason() == null ? statusError.getStatusCode().toString() : statusError.getReason());
        }
        if (error instanceof IOException) {
            log.warn("Route '{}' is unreachable: {}", route, error.toString());
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                    "Route '" + route + "' is unreachable");
        }
        log.error("Unhandled error on route '{}'", route, error);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected gateway error");
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route == null ? "none" : route.getId();
    }
}
