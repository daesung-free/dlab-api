package com.dlab.api.app.auth;

import jakarta.validation.constraints.NotBlank;

/** 인증 요청 DTO. 앱·관리자 웹이 같은 형태를 쓴다. */
public final class AuthRequests {

    private AuthRequests() {
    }

    public record Login(
            @NotBlank(message = "로그인 아이디는 필수입니다.") String loginId,
            @NotBlank(message = "비밀번호는 필수입니다.") String password) {
    }

    public record Refresh(
            @NotBlank(message = "refreshToken은 필수입니다.") String refreshToken) {
    }
}
