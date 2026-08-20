package com.dlab.api.app.auth;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 학생·학부모 앱 인증.
 *
 * <p>관리자 웹과 로직은 같지만 경로를 나눈다 — 클라이언트별로 인증 정책이 갈릴 수 있고
 * (예: 앱만 기기 등록 요구), 로그도 분리해서 봐야 한다.
 */
@Tag(name = "앱 · 인증 (A-1)")
@RestController
@RequestMapping("/api/v1/app/auth")
@RequiredArgsConstructor
public class AppAuthController {

    private final AuthService authService;

    /**
     * 로그인.
     *
     * <p><b>실패 5회면 계정이 잠긴다</b>(A-1). 잠금 해제는 관리자만 한다 — 시간이 지나도
     * 자동으로 풀리지 않는다.
     *
     * <p>임시 비밀번호 상태면 로그인은 되지만 <b>비밀번호를 바꾸기 전까지 다른 API가 전부
     * 막힌다</b>. 가입 승인 대기 중인 학생은 로그인 자체가 거부된다.
     */
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequests.Login request) {
        return ApiResponse.success(AuthResponse.from(
                authService.login(request.loginId(), request.password())));
    }

    /** Access Token 재발급. Refresh Token은 Redis에 있어 로그아웃하면 즉시 무효가 된다. */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody AuthRequests.Refresh request) {
        return ApiResponse.success(AuthResponse.from(
                authService.refresh(request.refreshToken())));
    }

    /**
     * 비밀번호 변경 (A-1 "임시 비밀번호 최초 로그인 시 변경 강제").
     *
     * <p>새 토큰을 함께 돌려준다 — 임시 비밀번호 플래그가 토큰 클레임에 있어서
     * 갱신하지 않으면 바꾸고도 계속 막힌다.
     */
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
     * <p><b>Access Token이 즉시 무효가 된다</b> — 블랙리스트에 올리고 요청마다 대조한다.
     * 만료를 기다리지 않는다.
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
