package com.dlab.api.app.auth;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;

/**
 * 학생·학부모 앱 인증.
 *
 * <p>관리자 웹과 로직은 같지만 경로를 나눈다 — 클라이언트별로 인증 정책이 갈릴 수 있고
 * (예: 앱만 기기 등록 요구), 로그도 분리해서 봐야 한다.
 */
@RestController
@RequestMapping("/api/v1/app/auth")
@RequiredArgsConstructor
public class AppAuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthRequests.Login request) {
        return ApiResponse.success(AuthResponse.from(
                authService.login(request.loginId(), request.password())));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody AuthRequests.Refresh request) {
        return ApiResponse.success(AuthResponse.from(
                authService.refresh(request.refreshToken())));
    }

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
