package com.dlab.api.admin.notification;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.notification.service.NotificationTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 알림 템플릿 관리 (실행가이드 P1-12).
 *
 * <p><b>전 지점 공통 참조값이라 최상위 관리자만 만진다.</b> 지점 관리자가 문구를 바꾸면
 * 다른 지점 알림까지 함께 바뀐다.
 */
@Tag(name = "관리자 · 알림 템플릿 (F-4.4-1)")
@RestController
@RequestMapping("/api/v1/admin/notification-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminNotificationTemplateController {

    private final NotificationTemplateService templateService;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;

    /** 전체 목록 — 이벤트 순으로 나와 A-C3 매핑표처럼 읽힌다. */
    @GetMapping
    public ApiResponse<List<NotificationTemplateResponse>> list() {
        return ApiResponse.success(templateService.findAll().stream()
                .map(this::view).toList());
    }

    /** 심사 진행 중인 것만. 카카오 심사(E-5)는 리드타임이 길어 따로 본다. */
    @GetMapping("/in-review")
    public ApiResponse<List<NotificationTemplateResponse>> inReview() {
        return ApiResponse.success(templateService.findInReview().stream()
                .map(this::view).toList());
    }

    /**
     * 알림 템플릿 등록.
     *
     * <p>⚠️ <b>학생명 변수가 필수다</b> — 다자녀 학부모가 "이거 누구 얘기지" 하고
     * 헷갈리지 않아야 한다. 빠지면 등록이 거부된다.
     */
    @PostMapping
    public ApiResponse<NotificationTemplateResponse> create(
            @Valid @RequestBody NotificationTemplateRequests.NotificationTemplateCreate request) {
        return ApiResponse.success(view(templateService.create(
                request.event(), request.channel(), request.recipientType(),
                request.requiredVariables())));
    }

    /**
     * 문구 수정.
     *
     * <p>⚠️ <b>승인된 알림톡 문구를 고치면 심사 상태가 `DRAFT`로 돌아간다.</b>
     * 카카오는 승인받은 문구 그대로만 발송을 허용한다.
     */
    @PatchMapping("/{id}/content")
    public ApiResponse<NotificationTemplateResponse> updateContent(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.UpdateContent request) {
        return ApiResponse.success(view(
                templateService.updateContent(id, request.titleTemplate(), request.bodyTemplate(),
                        request.requiredVariables(),
                        request.contentConfirmed() != null && request.contentConfirmed())));
    }

    /** 채널·수신자 (A-C3 매핑표). */
    @PatchMapping("/{id}/mapping")
    public ApiResponse<NotificationTemplateResponse> updateMapping(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.UpdateMapping request) {
        return ApiResponse.success(view(
                templateService.updateMapping(id, request.channel(), request.recipientType())));
    }

    /**
     * 템플릿 사용 여부.
     *
     * <p>문구 확정({@code content_confirmed})과 <b>카카오 심사 상태는 다른 축</b>이다 —
     * 문구가 확정돼도 심사를 못 넘으면 못 나간다.
     */
    @PutMapping("/{id}/active")
    public ApiResponse<NotificationTemplateResponse> changeActive(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.ChangeActive request) {
        return ApiResponse.success(view(
                templateService.changeActive(id, request.active())));
    }

    /** 카카오 심사 제출. 알림톡 템플릿만 대상이다. */
    @PostMapping("/{id}/review/submit")
    public ApiResponse<NotificationTemplateResponse> submitReview(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.SubmitReview request) {
        return ApiResponse.success(view(
                templateService.submitForReview(id, request.kakaoTemplateCode())));
    }

    /** 심사 결과 입력. 카카오와 자동 연동 창구가 없어 사람이 보고 넣는다(E-5). */
    @PostMapping("/{id}/review/result")
    public ApiResponse<NotificationTemplateResponse> applyReviewResult(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.ReviewResult request) {
        return ApiResponse.success(view(
                templateService.applyReviewResult(id, request.approved(), request.note())));
    }

    /** 수정자 이름을 붙인다. 템플릿은 수십 건이라 건마다 찾아도 된다. */
    private NotificationTemplateResponse view(com.dlab.domain.notification.entity.NotificationTemplate t) {
        String name = t.getUpdatedBy() == null || t.getUpdatedBy() == 0 ? null
                : accountRepository.findDisplayNames(List.of(t.getUpdatedBy())).stream()
                        .map(r -> (String) r[1]).findFirst().orElse(null);
        return NotificationTemplateResponse.from(t, name);
    }
}
