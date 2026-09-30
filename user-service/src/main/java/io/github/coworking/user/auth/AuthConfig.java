package io.github.coworking.user.auth;

import io.github.coworking.jwt.TokenIssuer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;

@Configuration
class AuthConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    TokenIssuer tokenIssuer(@Value("${jwt.secret}") String secret, @Value("${jwt.ttl}") Duration ttl, Clock clock) {
        return new TokenIssuer(secret, ttl, clock);
    }
}
