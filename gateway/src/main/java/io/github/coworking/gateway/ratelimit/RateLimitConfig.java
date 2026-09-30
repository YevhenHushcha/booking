package io.github.coworking.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

@Configuration
public class RateLimitConfig {

    /**
     * Authenticated requests are limited per user, so users behind one office NAT do not share a
     * bucket. Anonymous requests (login) fall back to the client IP.
     * <p>
     * Behind a load balancer the remote address is the balancer's; production would resolve the
     * client from {@code X-Forwarded-For} with {@code XForwardedRemoteAddressResolver}, trusting
     * only as many hops as there are proxies.
     */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(userId -> "user:" + userId)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
                }));
    }
}
