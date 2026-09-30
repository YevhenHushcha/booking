package io.github.coworking.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds {@code Retry-After} to 429 responses, which {@code RequestRateLimiter} does not do itself.
 * <p>
 * The wait is the time the bucket needs to refill one request's worth of tokens, computed from the
 * {@code X-RateLimit-*} headers the Redis limiter has already put on the response.
 */
@Component
public class RetryAfterFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            if (HttpStatus.TOO_MANY_REQUESTS.equals(response.getStatusCode())) {
                HttpHeaders headers = response.getHeaders();
                long requested = headerAsLong(headers, RedisRateLimiter.REQUESTED_TOKENS_HEADER, 1);
                long replenishRate = headerAsLong(headers, RedisRateLimiter.REPLENISH_RATE_HEADER, 1);
                long seconds = Math.max(1, (requested + replenishRate - 1) / replenishRate);
                headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static long headerAsLong(HttpHeaders headers, String name, long fallback) {
        String value = headers.getFirst(name);
        try {
            return value == null ? fallback : Math.max(1, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
