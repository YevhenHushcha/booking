package io.github.coworking.gateway.security;

import io.github.coworking.jwt.AuthHeaders;
import io.github.coworking.jwt.JwtContract;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns the verified token into the {@link AuthHeaders} the services read.
 * <p>
 * Incoming {@code X-Auth-*} headers are always dropped first, on public routes too: otherwise a
 * client could send {@code X-Auth-User-Id: 1} itself and a service would take it at face value.
 */
@Component
public class AuthHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> withHeaders(exchange, headers -> {
                    removeAuthHeaders(headers);
                    addIdentity(headers, ((JwtAuthenticationToken) principal).getToken());
                }))
                .defaultIfEmpty(withHeaders(exchange, AuthHeadersFilter::removeAuthHeaders))
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        // Before any routing filter, so no request can leave the gateway with client-supplied identity.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static ServerWebExchange withHeaders(ServerWebExchange exchange, Consumer<HttpHeaders> change) {
        return exchange.mutate().request(request -> request.headers(change)).build();
    }

    private static void removeAuthHeaders(HttpHeaders headers) {
        headers.remove(AuthHeaders.USER_ID);
        headers.remove(AuthHeaders.USER_EMAIL);
        headers.remove(AuthHeaders.USER_ROLES);
    }

    private static void addIdentity(HttpHeaders headers, Jwt jwt) {
        headers.set(AuthHeaders.USER_ID, jwt.getSubject());
        headers.set(AuthHeaders.USER_EMAIL, jwt.getClaimAsString(JwtContract.CLAIM_EMAIL));
        List<String> roles = jwt.getClaimAsStringList(JwtContract.CLAIM_ROLES);
        headers.set(AuthHeaders.USER_ROLES, roles == null ? "" : String.join(",", roles));
    }
}
