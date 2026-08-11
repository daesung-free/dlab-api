package com.dlab.api.app.approval;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApproverPreference;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.service.ApprovalQueryService;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.user.service.AppScopeResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 학부모 앱 — 승인 처리.
 *
 * <p>승인 대상은 클라이언트가 지정하지 않고 <b>토큰의 주체로 판단</b>한다.
 * 요청 본문으로 학생을 받으면 남의 자녀 건을 승인할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/app/approvals")
@RequiredArgsConstructor
public class AppApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalQueryService approvalQueryService;
    private final AppScopeResolver scopeResolver;

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

        var enrollment = scopeResolver.requireStudent(principal.accountId(), "승인자 선택");
        ApproverPreference saved = approvalService.choosePrimaryApprover(
                enrollment, request.preferred(), null);

        return ApiResponse.success(PrimaryApproverResponse.from(saved));
    }

    /** 현재 선택값. 없으면 아직 안 골랐다는 뜻이라 화면이 선택을 요구한다. */
    @GetMapping("/primary-approver")
    public ApiResponse<PrimaryApproverResponse> currentPrimaryApprover(
            @CurrentAccount AuthPrincipal principal) {

        var enrollment = scopeResolver.requireStudent(principal.accountId(), "승인자 선택");
        return ApiResponse.success(approvalService.findPrimaryApprover(enrollment.getId())
                .map(PrimaryApproverResponse::from)
                .orElse(null));
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
