package io.github.coworking.gateway.security;

import io.github.coworking.jwt.JwtContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;

import java.net.InetSocketAddress;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Actuator on its own port (docker-compose sets 9080) is left open: that port is not published
     * and only Prometheus and the healthcheck reach it, the same as a management port that no
     * Kubernetes Service or Ingress exposes. The condition matters: when actuator shares the public
     * port, as in IDE runs and tests, this chain does not exist and the main one guards actuator.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    SecurityWebFilterChain managementPortFilterChain(ServerHttpSecurity http,
                                                     @Value("${management.server.port}") int managementPort) {
        return http
                .securityMatcher(exchange -> {
                    InetSocketAddress local = exchange.getRequest().getLocalAddress();
                    return local != null && local.getPort() == managementPort
                            ? MatchResult.match()
                            : MatchResult.notMatch();
                })
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    /** Reachable without a token: logging in, and the probe docker-compose uses. */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/actuator/health",
            "/actuator/health/**",
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Stateless bearer-token API: no cookies, so no CSRF and no session to keep the context in.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Boot only auto-configures a decoder for asymmetric keys (JWK set or public key), so the
     * HS256 one is declared by hand. The default validators check {@code exp} and {@code nbf};
     * the issuer check is added on top.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(JwtContract.signingKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtContract.ISSUER));
        return decoder;
    }
}
