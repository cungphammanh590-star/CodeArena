package com.codearena.gateway.security;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 非 local 环境拒绝默认 JWT 密钥。 */
@Component
public class StrictSecretsGuard {

    private static final Logger log = LoggerFactory.getLogger(StrictSecretsGuard.class);
    private static final String DEFAULT_JWT = "codearena-dev-jwt-secret-change-me-32b";

    private final Environment environment;

    @Value("${codearena.auth.jwt-secret:}")
    private String jwtSecret;

    @Value("${codearena.security.strict-secrets:true}")
    private boolean strictSecrets;

    public StrictSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        Set<String> profiles = new HashSet<>(Arrays.asList(environment.getActiveProfiles()));
        boolean prodLike = profiles.contains("prod") || profiles.contains("production");
        if (!prodLike || !strictSecrets) {
            if (DEFAULT_JWT.equals(jwtSecret)) {
                log.warn(
                        "Using development default JWT secret; "
                                + "set CODEARENA_JWT_SECRET for production "
                                + "(profile prod + CODEARENA_STRICT_SECRETS=true).");
            }
            return;
        }
        if (jwtSecret == null
                || jwtSecret.isBlank()
                || DEFAULT_JWT.equals(jwtSecret)
                || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "Refuse to start: set a strong CODEARENA_JWT_SECRET "
                            + "(>=32 chars, not the dev default) for profile "
                            + profiles);
        }
    }
}
