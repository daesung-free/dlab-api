package com.dlab.api.app.approval;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApproverPreference;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.service.ApprovalQueryService;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.service.ParentSignupService;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 학부모 앱 — 승인 처리.
 *
 * <p>승인 대상은 클라이언트가 지정하지 않고 <b>토큰의 주체로 판단</b>한다.
 * 요청 본문으로 학생을 받으면 남의 자녀 건을 승인할 수 있다.
 */
@Tag(name = "앱 · 승인 처리 (학부모)")
@RestController
@RequestMapping("/api/v1/app/approvals")
@RequiredArgsConstructor
public class AppApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalQueryService approvalQueryService;
    private final AppScopeResolver scopeResolver;
    private final ParentSignupService parentSignupService;

    /** 내가 승인해야 할 대기 목록. */
    @GetMapping
    public ApiResponse<List<ApprovalResponse>> pending(@CurrentAccount AuthPrincipal principal) {
        return ApiResponse.success(
                approvalQueryService.pendingForGuardian(principal.accountId()).stream()
                        .map(ApprovalResponse::from)
                        .toList());
    }

    /**
     * 승인. 자격 검증(연결된 자녀인지)과 동시성 처리는 서비스가 담당한다 —
     * 담당선생님이 같은 순간 승인하면 여기서 409가 나간다.
     */
    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalResponse> approve(@CurrentAccount AuthPrincipal principal,
                                                 @PathVariable Long id) {
        return ApiResponse.success(
                ApprovalResponse.from(approvalService.approve(id, principal.accountId())));
    }

    /**
     * 반려.
     *
     * <p>승인과 같은 원자적 전이를 탄다 — 담당선생님이 같은 순간 처리하면 409가 나간다.
     */
    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalResponse> reject(@CurrentAccount AuthPrincipal principal,
                                                @PathVariable Long id,
                                                @Valid @RequestBody ApprovalRequests.Reject request) {
        return ApiResponse.success(ApprovalResponse.from(
                approvalService.reject(id, principal.accountId(), request.reason())));
    }

    /**
     * 우선 승인자 선택 + 동의 (0803 답변서).
     *
     * <p><b>학생 본인이 고른다.</b> 학부모가 고르면 "학부모를 승인자에서 빼는" 선택을
     * 학부모가 하게 되는데, 그건 승인 절차의 취지와 맞지 않는다.
     *
     * <p><b>동의 없이는 선택되지 않는다</b> — 요청 본문의 {@code agreed}가 false면 거절된다.
     * 선택과 동의는 한 번에 이뤄지고, 바꿀 때마다 <b>새 이력으로 쌓인다.</b>
     *
     * <p>안내 문구가 아직 미확정이라 동의한 문구 버전은 비워둔다. 확정되면 약관
     * ({@code terms})에 코드를 하나 넣고 여기서 연결한다.
     */
    @PostMapping("/primary-approver")
    public ApiResponse<PrimaryApproverResponse> choosePrimaryApprover(
            @CurrentAccount AuthPrincipal principal,
            @Valid @RequestBody ApprovalRequests.ChooseApprover request) {

        // ★★ 학부모만 고른다 (디랩 1029·1031). 원래 학생만 고를 수 있었는데
        //    ★ 반대가 맞다 — 승인권은 학부모의 것이므로 ★ 넘길지도 학부모가 정한다.
        //    학생이 「담임」을 고르면 ★ 학부모 모르게 학부모가 승인에서 빠진다.
        var enrollment = scopeResolver.requireGuardian(
                principal.accountId(), request.studentId(), "승인자 지정");
        ApproverPreference saved = approvalService.choosePrimaryApprover(
                enrollment, request.preferred(), null,
                scopeResolver.account(principal.accountId()));

        return ApiResponse.success(PrimaryApproverResponse.from(saved));
    }

    /**
     * ★★ <b>승인자를 지정해야 하는 자녀 목록</b> — 학부모 앱이 <b>로그인 직후</b> 받는다
     * (디랩 1031 ②).
     *
     * <h2>★ 앱이 화면을 띄울지 정한다 — 서버가 막지 않는다</h2>
     * 디랩 결정은 <b>「고르지 않으면 넘어갈 수 없다」</b> 인데, 그 강제는 <b>앱이 한다.</b>
     * ⚠️ 서버가 신청을 거부하게 만들면 <b>앱이 없는 학부모의 자녀가 아무것도 신청할 수
     * 없게 된다</b> — 같은 문서가 그 경로는 <b>지점 정책값으로 가라</b>고 했다.
     *
     * <h2>★ 이미 다른 보호자가 고른 자녀는 「지정됨」으로 온다</h2>
     * 부·모 중 <b>먼저 고른 값</b>이 현재값이므로, 나중에 들어온 보호자에게 화면을 또
     * 띄우면 <b>이미 정해진 것을 다시 묻는다.</b> 바꾸려면 설정 화면에서 한다.
     *
     * <p>★ {@code chosenByMe} 로 <b>내가 고른 것인지</b>를 함께 준다 — 설정 화면이
     * <b>「배우자가 정한 값입니다」</b>를 보여줄 수 있어야 하고, 그러지 않으면 바꾸는
     * 순간 상대에게 알림이 가는 이유를 이해할 수 없다.
     */
    @GetMapping("/primary-approver/children")
    public ApiResponse<List<ChildApproverView>> childrenNeedingApprover(
            @CurrentAccount AuthPrincipal principal) {

        var account = scopeResolver.account(principal.accountId());
        if (account.getGuardian() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "자녀 목록은 학부모만 조회할 수 있습니다.");
        }
        return ApiResponse.success(
                parentSignupService.children(account.getGuardian().getId()).stream()
                        .map(child -> {
                            var chosen = approvalService
                                    .findPrimaryApprover(child.enrollment().getId())
                                    .orElse(null);
                            return new ChildApproverView(
                                    child.student().getId(),
                                    child.student().getName(),
                                    chosen != null,
                                    chosen == null ? null : chosen.getPreferred(),
                                    chosen != null && chosen.getChosenByAccount() != null
                                            && chosen.getChosenByAccount().getId()
                                                    .equals(account.getId()));
                        })
                        .toList());
    }

    /**
     * @param needsChoice ★ {@code false} 면 <b>화면을 띄우지 않는다</b> — 이미 정해졌다
     * @param chosenByMe  ★ 내가 고른 것인가. {@code false} 면 <b>배우자가 정한 값</b>이다
     *                    (또는 지정 주체가 학부모로 바뀌기 전의 학생 선택분이다)
     */
    public record ChildApproverView(Long studentId, String studentName,
                                    boolean chosen, ApproverType preferred,
                                    boolean chosenByMe) {

        /** 앱이 읽기 쉽게 — 지정 화면을 띄울지. */
        public boolean needsChoice() {
            return !chosen;
        }
    }

    /**
     * 현재 선택값 + <b>지금 실제로 적용되는 승인자</b>.
     *
     * <p>{@code preferred}가 비어 있으면 아직 안 골랐다는 뜻이고, 그래도 {@code items}에는
     * <b>지점 정책값이 적용된 결과</b>가 들어 있다. 신청 화면은 이 값을 <b>표시만</b> 하면 된다 —
     * 거기서 바꾸게 하면 동의 기록이 신청 횟수만큼 쌓이고, 한 번 고른 학생에게는
     * 그 뒤로 관리자 지점 설정이 적용되지 않는다.
     */
    @GetMapping("/primary-approver")
    public ApiResponse<PrimaryApproverView> currentPrimaryApprover(
            @CurrentAccount AuthPrincipal principal,
            @RequestParam(required = false) Long studentId) {

        // ★ 조회는 학생·학부모 둘 다 한다. 학생은 ★ 표시만 한다(고르지는 못한다).
        var enrollment = scopeResolver.resolve(principal.accountId(), studentId);
        var chosen = approvalService.findPrimaryApprover(enrollment.getId()).orElse(null);

        return ApiResponse.success(new PrimaryApproverView(
                chosen == null ? null : chosen.getPreferred(),
                chosen == null ? null : chosen.getAgreedAt(),
                approvalService.effectiveApprovers(enrollment).stream()
                        .map(EffectiveApproverView::from).toList()));
    }

    /**
     * @param preferred 학생이 고른 값. <b>비어 있으면 아직 안 골랐다</b>
     * @param items     유형별로 지금 적용되는 승인자
     */
    public record PrimaryApproverView(ApproverType preferred, java.time.Instant agreedAt,
                                      java.util.List<EffectiveApproverView> items) {
    }

    /**
     * @param source        {@code STUDENT_CHOICE}면 학생 선택이 지점 설정을 덮고 있다는 뜻
     * @param branchDefault 지점 정책값. {@code effective}와 다르면 선택이 덮은 것이다
     */
    public record EffectiveApproverView(
            com.dlab.domain.approval.entity.RequestType requestType,
            ApproverType effective,
            com.dlab.domain.approval.service.ApprovalService.ApproverSource source,
            ApproverType branchDefault,
            Short timeoutMinutes,
            ApproverType escalationApproverType) {

        static EffectiveApproverView from(
                com.dlab.domain.approval.service.ApprovalService.EffectiveApprover e) {
            return new EffectiveApproverView(e.requestType(), e.effective(), e.source(),
                    e.branchDefault(), e.timeoutMinutes(), e.escalationApproverType());
        }
    }

    /**
     * 선택 결과.
     *
     * @param preferred 고른 승인자
     * @param agreedAt  동의한 시각. 이 값이 곧 동의 근거다
     */
    public record PrimaryApproverResponse(ApproverType preferred, java.time.Instant agreedAt) {

        static PrimaryApproverResponse from(ApproverPreference p) {
            return new PrimaryApproverResponse(p.getPreferred(), p.getAgreedAt());
        }
    }
}
