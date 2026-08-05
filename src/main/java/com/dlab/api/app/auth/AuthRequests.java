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

    /**
     * 비밀번호 변경. 임시 비밀번호 강제 변경도 같은 요청을 쓴다.
     *
     * <p>길이·조합 검증은 {@code @Size} 같은 어노테이션이 아니라
     * {@link com.dlab.common.security.PasswordPolicy}가 한다 — 정책이 아직 미확정(NF-05 미확보)이라
     * 한 곳에 모아둬야 확정 시 한 번에 바뀐다.
     */
    public record ChangePassword(
            @NotBlank(message = "현재 비밀번호는 필수입니다.") String currentPassword,
            @NotBlank(message = "새 비밀번호는 필수입니다.") String newPassword) {
    }
}
