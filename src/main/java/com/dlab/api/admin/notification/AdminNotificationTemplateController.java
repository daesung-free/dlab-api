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
    private final com.dlab.domain.notification.service.AlimtalkTemplateRegistrar registrar;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;

    /**
     * 전체 목록 — 이벤트 순으로 나와 A-C3 매핑표처럼 읽힌다.
     *
     * <p>★ {@code manual=true} 면 <b>직원 수동 발송 대상만</b> 내린다(디랩 1016 「직원 발송」).
     * 화면이 이벤트 이름으로 추측하면 템플릿이 늘 때마다 화면을 고쳐야 하므로
     * <b>서버가 가린다.</b>
     *
     * <p>⚠️ {@code sendable = false} 인 것도 함께 담는다 — 빼면 담당자가 「급식 안내가
     * 왜 없지」를 겪고, 고를 수 있게 두면 보냈는데 안 나간다. 화면이 <b>보여주되 고르지
     * 못하게</b> 하는 것이 맞다.
     *
     * <p>★ 수동 발송 목록은 <b>발송을 실제로 하는 직원</b>이 봐야 하므로 역할을 넓힌다.
     * 전체 목록(문구 관리용)은 종전대로 최상위 관리자만 본다 — 전 지점 공통 참조값이라
     * 지점 담당자가 문구를 바꾸면 다른 지점 알림까지 함께 바뀐다.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<NotificationTemplateResponse>> list(
            @com.dlab.common.security.CurrentAccount
            com.dlab.common.security.AuthPrincipal me,
            @RequestParam(required = false) Boolean manual) {
        if (Boolean.TRUE.equals(manual)) {
            return ApiResponse.success(templateService.findManual().stream()
                    .map(this::view).toList());
        }
        if (!me.hasRole(com.dlab.common.security.Role.SUPER_ADMIN)) {
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.FORBIDDEN);
        }
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

    /**
     * 카카오 심사 제출 — <b>알리고 콘솔에서 받은 코드를 손으로 넣는 경로</b>다.
     *
     * <p>⚠️ <b>보통은 {@code /aligo/register} 를 쓴다</b> — 그쪽은 등록·검수요청·코드
     * 기록을 한 번에 한다. 이 경로는 <b>콘솔에서 이미 만들어 둔 템플릿</b>을 잇는 용도다.
     */
    @PostMapping("/{id}/review/submit")
    public ApiResponse<NotificationTemplateResponse> submitReview(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.SubmitReview request) {
        return ApiResponse.success(view(
                templateService.submitForReview(id, request.kakaoTemplateCode())));
    }

    /**
     * 심사 결과 입력 — <b>사람이 보고 넣는 경로</b>다.
     *
     * <p>⚠️ <b>「카카오와 자동 연동 창구가 없다」는 전제가 틀렸다</b>(2026-10-31 확인) —
     * 알리고에 <b>목록 조회 API 가 있어</b> {@code /aligo/sync} 로 되읽을 수 있다.
     * 이 경로는 <b>동기화가 못 읽는 상태</b>를 사람이 넣는 용도로 남긴다.
     */
    @PostMapping("/{id}/review/result")
    public ApiResponse<NotificationTemplateResponse> applyReviewResult(
            @PathVariable Long id,
            @Valid @RequestBody NotificationTemplateRequests.ReviewResult request) {
        return ApiResponse.success(view(
                templateService.applyReviewResult(id, request.approved(), request.note())));
    }

    /**
     * ★★ <b>알리고에 등록하고 검수를 요청한다</b> — 한 번에 (디랩 1031 2번).
     *
     * <h2>★ 손으로 옮기지 않는다</h2>
     * ⚠️ 알림톡은 <b>한 글자라도 다르면 발송이 실패한다.</b> 콘솔에 옮겨 적으면
     * <b>틀릴 자리</b>가 생기는데, 이 경로는 <b>DB 에 있는 문구를 그대로</b> 보낸다.
     * 받은 코드도 <b>그대로 박힌다</b> — 사람이 받아 적는 단계가 사라진다.
     *
     * <h2>⚠️ 문구가 확정되지 않으면 거부한다</h2>
     * 검수에 올린 뒤에는 <b>문구를 고치려면 처음부터</b>이고 <b>4~5일이 또 걸린다.</b>
     * ★ 지금 <b>등록비 안내가 그 상태</b>다(디랩 문구 회신 대기).
     *
     * <h2>⚠️ 테스트 모드와 무관하다</h2>
     * 등록은 발송이 아니라 형상 관리라 <b>테스트 모드를 켜 둬도 실제로 등록된다.</b>
     */
    @PostMapping("/{id}/aligo/register")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<RegisterResult> registerToAligo(@PathVariable Long id) {
        String code = registrar.registerAndRequestReview(id);
        return ApiResponse.success(new RegisterResult(code, code != null));
    }

    /**
     * ★★ 알리고 목록을 읽어 <b>심사 상태를 되읽는다</b>.
     *
     * <p>★ 지금까지 <b>사람이 콘솔을 보고 넣어야</b> 했다 — 목록 API 가 있으므로
     * 그 단계가 필요 없다.
     *
     * <p>⚠️ <b>모르는 상태값은 건드리지 않는다</b> — 반려로 읽으면
     * <b>멀쩡한 템플릿이 반려로 뒤집힌다.</b>
     */
    @PostMapping("/aligo/sync")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<Integer> syncFromAligo() {
        return ApiResponse.success(registrar.syncReviewStatus());
    }

    /**
     * @param templateCode 알리고가 부여한 코드
     * @param linked       ★ {@code false} 면 <b>등록은 됐으나 코드를 못 받았다</b> —
     *                     {@code /aligo/sync} 로 확인해야 한다
     */
    public record RegisterResult(String templateCode, boolean linked) {
    }

    /** 수정자 이름을 붙인다. 템플릿은 수십 건이라 건마다 찾아도 된다. */
    private NotificationTemplateResponse view(com.dlab.domain.notification.entity.NotificationTemplate t) {
        String name = t.getUpdatedBy() == null || t.getUpdatedBy() == 0 ? null
                : accountRepository.findDisplayNames(List.of(t.getUpdatedBy())).stream()
                        .map(r -> (String) r[1]).findFirst().orElse(null);
        return NotificationTemplateResponse.from(t, name);
    }
}
