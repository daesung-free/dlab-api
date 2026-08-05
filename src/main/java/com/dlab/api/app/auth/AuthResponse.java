package com.dlab.api.app.auth;

import com.dlab.domain.user.service.AuthService;

/**
 * 토큰 응답.
 *
 * @param mustChangePassword 임시 비밀번호 상태(A-1). {@code true}면 앱이 비밀번호 변경 화면으로
 *                           보내야 한다. 서버도 변경 전까지 다른 API를 막지만
 *                           ({@code PasswordChangeRequiredInterceptor}), 앱이 미리 알면
 *                           403을 받고 나서야 화면을 바꾸는 왕복을 없앨 수 있다
 */
public record AuthResponse(String accessToken, String refreshToken, String tokenType,
                           boolean mustChangePassword) {

    public static AuthResponse from(AuthService.TokenPair pair) {
        return new AuthResponse(pair.accessToken(), pair.refreshToken(), "Bearer",
                pair.mustChangePassword());
    }
}
