package com.dlab.api.app.appconfig;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.appconfig.entity.Platform;
import com.dlab.domain.appconfig.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 부팅 설정 (A-21 · F-4.12-3).
 *
 * <p><b>앱이 켜지자마자 부르는 첫 API다.</b> 그래서 <b>인증 없이</b> 열려 있다 —
 * 점검 중이거나 강제 업데이트가 필요한 상황은 로그인 전에 판단해야 하고,
 * 로그인 API 자체가 점검으로 막혀 있을 수도 있다.
 */
@Tag(name = "앱 · 부팅 설정 (A-21)")
@RestController
@RequestMapping("/api/v1/app/app-config")
@RequiredArgsConstructor
public class AppConfigController {

    private final AppConfigService appConfigService;

    /**
     * 앱 부팅 시 첫 호출 — 점검 여부·최소 지원 버전.
     *
     * <p><b>로그인 전에 부른다.</b> 점검 중이면 로그인 API 자체가 막혀 있을 수 있어,
     * 그때 무슨 일인지 알려줄 창구가 이것뿐이다.
     *
     * @param version 앱 자기 버전. 없으면 강제 업데이트 판정을 하지 않는다 —
     *                못 읽었다고 막아버리면 파싱 문제 하나가 전 사용자 차단이 된다
     */
    @GetMapping
    public ApiResponse<AppConfigResponse.Boot> boot(
            @RequestParam Platform platform,
            @RequestParam(required = false) String version) {
        return ApiResponse.success(
                AppConfigResponse.Boot.from(appConfigService.boot(platform, version)));
    }
}
