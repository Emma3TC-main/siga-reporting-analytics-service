package utp.siga.reporting.infrastructure.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import utp.siga.reporting.presentation.error.Problems;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${reporting.security.jwks-uri}") String jwks,
            @Value("${reporting.security.issuer}") String issuer,
            @Value("${reporting.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> valid(jwt, audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")
                    .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/reconciliation")
                    .access(allAuthorities("REPORT_READ", "CATALOG_READ", "INVENTORY_READ"))
                .requestMatchers(HttpMethod.GET, "/api/v1/dashboard", "/api/v1/reports/**")
                    .hasAuthority("REPORT_READ")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, ex) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")))
            .oauth2ResourceServer(resource -> resource
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req, res, ex) -> Problems.write(req, res, 401, "AUTH_INVALID"))
                .accessDeniedHandler((req, res, ex) -> Problems.write(req, res, 403, "AUTH_FORBIDDEN")));
        return http.build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> allAuthorities(String... required) {
        Set<String> permissions = Set.of(required);
        return (authentication, context) -> {
            Set<String> actual = authentication.get().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(java.util.stream.Collectors.toSet());
            return new AuthorizationDecision(actual.containsAll(permissions));
        };
    }

    private static boolean valid(Jwt jwt, String audience) {
        if (jwt.getExpiresAt() == null || jwt.getSubject() == null || !jwt.getAudience().contains(audience)) {
            return false;
        }
        try {
            UUID.fromString(jwt.getSubject());
            return true;
        } catch (IllegalArgumentException invalidSubject) {
            return false;
        }
    }
}
