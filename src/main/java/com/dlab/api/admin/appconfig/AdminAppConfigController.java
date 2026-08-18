package com.dlab.api.admin.appconfig;

import com.dlab.api.app.appconfig.AppConfigResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.domain.appconfig.entity.Platform;
import com.dlab.domain.appconfig.service.AppConfigService;
import com.dlab.domain.appconfig.service.TermsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 웹 — 앱 버전·점검 모드·약관 관리 (F-4.12-3).
 *
 * <p><b>전 지점 공통 설정이라 최상위 관리자만 만진다.</b> 지점 관리자가 점검 모드를 켜면
 * 다른 지점 앱까지 전부 멈춘다 — 공휴일 등록에서 전 지점 공통을 본사만 넣게 한 것과 같은 이유다.
 */
@RestController
@RequestMapping("/api/v1/admin/app-config")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAppConfigController {

    private final AppConfigService appConfigService;
    private final TermsService termsService;

    @GetMapping
    public ApiResponse<List<AppConfigResponse.Detail>> list() {
        return ApiResponse.success(appConfigService.findAll().stream()
                .map(AppConfigResponse.Detail::from).toList());
    }

    /** 버전 설정. {@code null} 필드는 "변경하지 않음"이다. */
    @PatchMapping("/{platform}/versions")
    public ApiResponse<AppConfigResponse.Detail> updateVersions(
            @PathVariable Platform platform,
            @Valid @RequestBody AdminAppConfigRequests.UpdateVersions request) {
        return ApiResponse.success(AppConfigResponse.Detail.from(
                appConfigService.updateVersions(platform, request.minVersion(), request.latestVersion())));
    }

    /**
     * 점검 모드 전환.
     *
     * <p>⚠️ <b>켜는 순간 그 플랫폼 전 사용자가 앱을 못 쓴다.</b>
     */
    @PutMapping("/{platform}/maintenance")
    public ApiResponse<AppConfigResponse.Detail> changeMaintenance(
            @PathVariable Platform platform,
            @Valid @RequestBody AdminAppConfigRequests.ChangeMaintenance request) {
        return ApiResponse.success(AppConfigResponse.Detail.from(appConfigService.changeMaintenance(
                platform, request.maintenance(), request.message(), request.until())));
    }

    // ── 약관 ─────────────────────────────────────────────────────

    /** @param academyId 지정하면 그 지점 전용 약관까지 본다. 비우면 공통본만 */
    @GetMapping("/terms")
    public ApiResponse<List<AdminTermsResponse>> terms(
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(termsService.currentTerms(academyId).stream()
                .map(AdminTermsResponse::from).toList());
    }

    /**
     * 약관 등록.
     *
     * <p><b>기존 문구를 고치는 API는 없다.</b> 덮어쓰면 이미 동의한 사람들의 근거가 사라진다 —
     * 개정은 버전을 올려 새로 등록하는 것이다.
     */
    @PostMapping("/terms")
    public ApiResponse<AdminTermsResponse> createTerms(
            @Valid @RequestBody AdminAppConfigRequests.CreateTerms request) {
        return ApiResponse.success(AdminTermsResponse.from(termsService.create(
                request.academyId(),
                request.code(), request.version(), request.title(), request.content(),
                request.required() == null || request.required(), request.effectiveAt())));
    }

    /** 특정 계정의 동의 이력 — 분쟁 대응. */
    @GetMapping("/terms/agreements/{accountId}")
    public ApiResponse<List<AgreementHistoryResponse>> agreements(@PathVariable Long accountId) {
        return ApiResponse.success(termsService.history(accountId).stream()
                .map(AgreementHistoryResponse::from).toList());
    }

    public record AdminTermsResponse(Long id, Long academyId, String code, String version,
                                     String title, boolean required, Instant effectiveAt) {
        static AdminTermsResponse from(com.dlab.domain.appconfig.entity.Terms terms) {
            return new AdminTermsResponse(terms.getId(),
                    terms.getAcademy() == null ? null : terms.getAcademy().getId(),
                    terms.getCode(), terms.getVersion(),
                    terms.getTitle(), terms.isRequired(), terms.getEffectiveAt());
        }
    }

    /** 동의 이력 한 줄. <b>약관 버전이 함께 나온다</b> — 그게 이 화면의 존재 이유다. */
    public record AgreementHistoryResponse(String code, String version, boolean agreed,
                                           Instant agreedAt) {
        static AgreementHistoryResponse from(com.dlab.domain.appconfig.entity.TermAgreement a) {
            return new AgreementHistoryResponse(a.getTerms().getCode(), a.getTerms().getVersion(),
                    a.isAgreed(), a.getAgreedAt());
        }
    }
}
