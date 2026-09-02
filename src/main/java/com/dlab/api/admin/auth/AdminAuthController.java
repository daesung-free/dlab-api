package com.dlab.api.admin.auth;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.api.app.auth.AuthRequests;
import com.dlab.api.app.auth.AuthResponse;
import com.dlab.domain.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 인증.
 *
 * <p>앱과 로직은 같지만 경로를 나눈다 — 클라이언트별로 인증 정책이 갈릴 수 있고
 * (예: 앱만 기기 등록 요구), 로그도 분리해서 봐야 한다.
 */
@Tag(name = "관리자 · 인증")
@RestController
@RequestMapping("/api/v1/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    /**
     * 관리자 로그인.
     *
     * <p>실패가 5회 쌓이면 계정이 잠긴다 — <b>자동 해제는 없고</b> 관리자가 풀어야 한다.
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequests.Login request) {
        return ApiResponse.success(AuthResponse.from(
                authService.login(request.loginId(), request.password())));
    }

    /** 액세스 토큰 재발급. Refresh Token은 Redis에 있다. */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody AuthRequests.Refresh request) {
        return ApiResponse.success(AuthResponse.from(
                authService.refresh(request.refreshToken())));
    }

    /** 비밀번호 변경. 관리자도 임시 비밀번호를 재발급받으면 변경 강제 대상이 된다. */
    @PostMapping("/password")
    public ApiResponse<AuthResponse> changePassword(
            @CurrentAccount AuthPrincipal principal,
            @Valid @RequestBody AuthRequests.ChangePassword request) {
        return ApiResponse.success(AuthResponse.from(authService.changePassword(
                principal.accountId(), request.currentPassword(), request.newPassword())));
    }

    /**
     * 로그아웃.
     *
     * <p>Access Token을 블랙리스트에 올린다 — <b>안 그러면 만료까지 계속 쓸 수 있다</b>.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@CurrentAccount AuthPrincipal principal,
                                    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String header) {
        authService.logout(principal.accountId(), stripBearer(header));
        return ApiResponse.empty();
    }

    private String stripBearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
