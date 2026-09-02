package com.dlab.api.kiosk.auth;

import com.dlab.api.kiosk.dto.DsaRefreshRequest;
import com.dlab.api.kiosk.dto.DsaTokenRequest;
import com.dlab.api.kiosk.dto.DsaTokenResponse;
import com.dlab.domain.kiosk.service.DsaTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 인증. <b>경로에 {@code /api/v1} prefix를 붙이지 않는다</b> —
 * 키오스크가 {@code /auth/token}을 하드코딩하고 있고 그 코드는 수정하지 않기로 했다
 * (CLAUDE.md §5 경로 규칙의 명시적 예외).
 *
 * <p>응답도 {@code ApiResponse}가 아니다. {@link DsaTokenResponse} 참고.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class DsaAuthController {

    private final DsaTokenService dsaTokenService;

    /**
     * DSA 호환 토큰 발급 (규격서 3.1).
     *
     * <p>키오스크가 <b>본문에</b> 자격증명을 실어 보낸다 — 헤더가 아니다.
     * 실패도 401이 아니라 {@code code:910}으로 내려간다.
     */
    @PostMapping("/token")
    public DsaTokenResponse issue(@RequestBody DsaTokenRequest request) {
        var issued = dsaTokenService.issue(request.acadCd(), request.clientId(), request.secretId());
        return DsaTokenResponse.of(issued.token(), issued.refreshToken());
    }

    /**
     * 토큰 재발급 (규격서 3.2).
     *
     * <p>만료는 <b>정상 흐름</b>이다 — 키오스크가 이걸 부르고 재시도한다.
     */
    @PostMapping("/refreshToken")
    public DsaTokenResponse refresh(@RequestBody DsaRefreshRequest request) {
        var issued = dsaTokenService.refresh(request.clientId(), request.refreshToken());
        return DsaTokenResponse.of(issued.token(), issued.refreshToken());
    }
}
