package com.dlab.api.app.appconfig;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.appconfig.service.AppNotificationSettingService;
import com.dlab.domain.appconfig.service.TermsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 앱 설정 — 약관 동의 · 알림 수신 · 푸시 토큰 (A-21).
 *
 * <p>비밀번호 변경·로그아웃은 {@code /api/v1/app/auth}에 이미 있다.
 */
@Tag(name = "앱 · 설정 — 약관·알림·푸시 (A-21)")
@RestController
@RequestMapping("/api/v1/app/settings")
@RequiredArgsConstructor
public class AppSettingsController {

    private final TermsService termsService;
    private final AppNotificationSettingService notificationSettingService;

    // ── 약관 ─────────────────────────────────────────────────────

    /** 시행 중인 약관 + 내 동의 상태. 개정되면 새 버전이 미동의로 다시 뜬다. */
    @GetMapping("/terms")
    public ApiResponse<List<SettingsResponse.TermsStatus>> terms(@CurrentAccount AuthPrincipal me) {
        return ApiResponse.success(termsService.statusOf(me.accountId()).stream()
                .map(SettingsResponse.TermsStatus::from).toList());
    }

    /** 동의·철회. 같은 값으로 다시 눌러도 이력은 남는다. */
    @PostMapping("/terms/{termsId}")
    public ApiResponse<Void> agree(@CurrentAccount AuthPrincipal me,
                                   @PathVariable Long termsId,
                                   @Valid @RequestBody SettingsRequests.Agree request) {
        termsService.record(me.accountId(), termsId, request.agreed());
        return ApiResponse.empty();
    }

    // ── 알림 수신 ────────────────────────────────────────────────

    /**
     * 알림 수신 설정 (A-21).
     *
     * <p><b>행이 없으면 수신</b>이다. 유형이 추가될 때마다 기존 계정에 행을 채워 넣는 방식은,
     * 한 번 빠뜨리면 새 알림이 아무에게도 안 간다.
     */
    @GetMapping("/notifications")
    public ApiResponse<List<SettingsResponse.Preference>> notifications(
            @CurrentAccount AuthPrincipal me) {
        return ApiResponse.success(notificationSettingService.preferences(me.accountId()).stream()
                .map(SettingsResponse.Preference::from).toList());
    }

    /**
     * 유형별 수신 on/off.
     *
     * <p><b>필수 알림은 끌 수 없다</b> — 미등원처럼 안전에 걸리는 것은 거부된다.
     */
    @PutMapping("/notifications/{event}")
    public ApiResponse<Void> changeNotification(
            @CurrentAccount AuthPrincipal me,
            @PathVariable com.dlab.domain.notification.entity.NotificationEvent event,
            @Valid @RequestBody SettingsRequests.ToggleNotification request) {
        notificationSettingService.changePreference(me.accountId(), event, request.enabled());
        return ApiResponse.empty();
    }

    // ── FCM 토큰 ─────────────────────────────────────────────────

    /**
     * 토큰 등록·갱신. 앱의 토큰 갱신 리스너가 부른다.
     *
     * <p>⚠️ <b>등록해도 아직 발송되지 않는다</b> — Firebase 프로젝트·APNs 키(E-7) 대기.
     */
    @PostMapping("/push-token")
    public ApiResponse<Void> registerPushToken(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody SettingsRequests.RegisterPushToken request) {
        notificationSettingService.registerToken(me.accountId(), request.token(), request.platform());
        return ApiResponse.empty();
    }

    /** 로그아웃·앱 삭제 시 해제. */
    @DeleteMapping("/push-token")
    public ApiResponse<Void> removePushToken(
            @Valid @RequestBody SettingsRequests.RemovePushToken request) {
        notificationSettingService.removeToken(request.token());
        return ApiResponse.empty();
    }
}
