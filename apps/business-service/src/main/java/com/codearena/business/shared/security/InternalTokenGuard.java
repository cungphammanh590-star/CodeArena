package com.codearena.business.shared.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 内网调用 token 校验（与业务域无关）。 */
@Component
public class InternalTokenGuard {

    @Value("${codearena.internal.token:codearena-internal-dev}")
    private String internalToken;

    public void assertValid(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            return;
        }
        if (token == null || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid internal token");
        }
    }
}
