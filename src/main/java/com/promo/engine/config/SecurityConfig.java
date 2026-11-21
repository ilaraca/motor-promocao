package com.promo.engine.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Configuration
public class SecurityConfig {

    @Bean
    public SecretKey jwtSecretKey(@Value("${promotions.security.jwt-secret}") String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public LocalJwtTokenFactory localJwtTokenFactory(SecretKey jwtSecretKey) {
        return new LocalJwtTokenFactory(jwtSecretKey);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/v1/admin/**").hasAuthority("SCOPE_promotions:admin:write")
                        .requestMatchers("/v1/promotions/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Helper to issue signed local HS256 JWTs for POC testing, curls, and automated integration tests.
     */
    public static final class LocalJwtTokenFactory {
        private final SecretKey secretKey;

        public LocalJwtTokenFactory(SecretKey secretKey) {
            this.secretKey = secretKey;
        }

        public String issueToken(String userId, Set<String> segments, Set<String> scopes) {
            try {
                Instant now = Instant.now();
                JWTClaimsSet claims = new JWTClaimsSet.Builder()
                        .subject(userId)
                        .claim("segments", List.copyOf(segments))
                        .claim("scope", String.join(" ", scopes))
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build();

                SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
                signedJwt.sign(new MACSigner(secretKey));
                return signedJwt.serialize();
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to sign local JWT", ex);
            }
        }
    }
}
