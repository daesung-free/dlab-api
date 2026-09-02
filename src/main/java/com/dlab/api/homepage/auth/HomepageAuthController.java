package com.dlab.api.homepage.auth;

import com.dlab.api.kiosk.dto.DsaRefreshRequest;
import com.dlab.api.kiosk.dto.DsaTokenResponse;
import com.dlab.api.homepage.dto.HomepageRequests;
import com.dlab.domain.admission.service.HomepageTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈페이지 입학예약 인증 (규격서 3.1·3.2).
 *
 * <p><b>경로가 {@code /auth2}다</b> — 키오스크의 {@code /auth}와 다르다. 홈페이지가
 * 그 주소를 하드코딩하고 있고 코드를 수정하지 않기로 했다.
 *
 * <p>응답 형식은 키오스크 토큰과 같아서 {@link DsaTokenResponse}를 그대로 쓴다.
 */
@RestController
@RequestMapping("/auth2")
@RequiredArgsConstructor
public class HomepageAuthController {

    private final HomepageTokenService tokenService;

    @PostMapping("/token")
    public DsaTokenResponse issue(@RequestBody HomepageRequests.Token request) {
        var issued = tokenService.issue(request.clientId(), request.secretId(), request.service());
        return DsaTokenResponse.of(issued.token(), issued.refreshToken());
    }

    @PostMapping("/refreshToken")
    public DsaTokenResponse refresh(@RequestBody DsaRefreshRequest request) {
        var issued = tokenService.refresh(request.clientId(), request.refreshToken());
        return DsaTokenResponse.of(issued.token(), issued.refreshToken());
    }
}
