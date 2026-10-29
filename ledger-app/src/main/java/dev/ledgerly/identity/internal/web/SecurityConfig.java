package dev.ledgerly.identity.internal.web;

import dev.ledgerly.identity.internal.application.JwtIssuer;
import dev.ledgerly.identity.internal.application.JwtKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what. Requests carry a JWT issued by {@code /v1/auth/login}: there are no sessions and no cookies, so
 * there is nothing for CSRF to forge.
 *
 * <p>Signing up and in, browsing events, watching tickets sell over {@code /ws}, the API document and the actuator endpoints the application exposes are
 * open. Every other
 * request needs a valid token. What a signed-in user may do with a particular wallet is decided by the module that owns
 * it, not here.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProblemAuthenticationEntryPoint unauthenticated) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, "/v1/auth/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/events", "/v1/events/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/ws")
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/metrics/**")
                        .permitAll()
                        .requestMatchers(
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(unauthenticated)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleFromClaim())))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthenticated))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${ledgerly.security.jwt.secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(JwtKeys.secretKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /** The {@code role} claim becomes the authority {@code ROLE_<role>}. */
    private static JwtAuthenticationConverter roleFromClaim() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtIssuer.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
