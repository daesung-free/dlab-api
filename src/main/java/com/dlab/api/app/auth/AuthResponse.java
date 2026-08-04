package com.dlab.api.app.auth;

import com.dlab.domain.user.service.AuthService;

/** 토큰 응답. */
public record AuthResponse(String accessToken, String refreshToken, String tokenType) {

    public static AuthResponse from(AuthService.TokenPair pair) {
        return new AuthResponse(pair.accessToken(), pair.refreshToken(), "Bearer");
    }
}
