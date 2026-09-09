package com.dlab.api.admin.approval;

import com.dlab.api.app.approval.ApprovalRequests;
import com.dlab.api.app.approval.ApprovalResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.service.ApprovalQueryService;
import com.dlab.domain.approval.service.ApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 담당선생님(사감) 승인 처리.
 *
 * <p>타임아웃(10분) 전에도 승인할 수 있다. 다만 그 경우 학부모에게 나가는 문구가
 * 달라야 하므로({@code STAFF_BEFORE_TIMEOUT}) 서비스가 케이스를 판별해 기록한다.
 */
@Tag(name = "관리자 · 승인 처리 (F-4.11-5)")
@RestController
@RequestMapping("/api/v1/admin/approvals")
@RequiredArgsConstructor
// ★ 담임뿐 아니라 관리자도 처리한다 (F-4.1-6 "실시간 확인/수정·승인").
//   담임만 열어두면 관리자 웹의 승인 대기 화면이 조회 전용이 된다.
//   실제 승인 자격(그 요청의 담임 본인인가 / 같은 지점 직원인가)은
//   ApprovalService.resolveApproverType 이 다시 확인한다 — 여기는 화면 진입만 연다.
//   ⚠️ 어디까지 열지는 I-12(승인 주체 매트릭스) 대기다.
@PreAuthorize("hasAnyRole('TEACHER','SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalQueryService approvalQueryService;

    /** 내가 담당선생님인 승인 대기 목록. */
    @GetMapping
    public ApiResponse<List<ApprovalResponse>> pending(@CurrentAccount AuthPrincipal principal) {
        return ApiResponse.success(
                approvalQueryService.pendingForTeacher(principal.accountId()).stream()
                        .map(ApprovalResponse::from)
                        .toList());
    }

    /**
     * 승인(담당선생님).
     *
     * <p>상태 전이는 <b>원자적</b>이다 — 학부모 승인과 정확히 같은 순간 들어와도
     * 한쪽만 성공한다. 어느 케이스인지는 <b>승인 시각을 신청시각+타임아웃과 비교</b>해
     * 판별하고, 학부모에게 나가는 문구가 케이스마다 다르다(§3).
     */
    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalResponse> approve(@CurrentAccount AuthPrincipal principal,
                                                 @PathVariable Long id) {
        return ApiResponse.success(
                ApprovalResponse.from(approvalService.approve(id, principal.accountId())));
    }

    /** 반려. 사유가 학생·학부모에게 그대로 전달된다. */
    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalResponse> reject(@CurrentAccount AuthPrincipal principal,
                                                @PathVariable Long id,
                                                @Valid @RequestBody ApprovalRequests.Reject request) {
        return ApiResponse.success(ApprovalResponse.from(
                approvalService.reject(id, principal.accountId(), request.reason())));
    }

    /**
     * 승인 철회 — <b>승인된 건을 되돌린다</b>.
     *
     * <p>앱의 취소({@code DELETE /app/.../absence-reasons/{id}})와 다르다.
     * 그쪽은 <b>승인 전까지만</b> 되고 신청자가 스스로 거두는 것이다.
     *
     * <p><b>학생에게 이 경로를 주지 않는다.</b> 사유 신청이 승인되면 그 시간 결석·조퇴가
     * 무단이 아니게 되어 벌점을 면하는데, 학생이 직접 되돌릴 수 있으면
     * <b>승인만 받고 취소해서 벌점을 피하는 길</b>이 생긴다.
     *
     * <p>그리고 취소 사유에 따라 옳은 결과가 반대다 — "병원에 안 가게 됐다"면 정상
     * 등원이니 벌점이 없는 게 맞고, "잘못 신청했다"면 원래 무단이라 붙는 게 맞다.
     * 사람이 판단해야 하는 자리다.
     *
     * <p><b>사유는 필수</b>다. 승인을 되돌린 기록에 이유가 없으면 나중에 "왜 무른 거냐"에
     * 답할 수 없다.
     */
    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
    public ApiResponse<Void> revoke(@CurrentAccount AuthPrincipal principal,
                                    @PathVariable Long id,
                                    @Valid @RequestBody RevokeRequest request) {
        approvalService.revoke(id, principal, request.reason());
        return ApiResponse.empty();
    }

    public record RevokeRequest(
            @jakarta.validation.constraints.NotBlank(message = "철회 사유는 필수입니다.")
            @jakarta.validation.constraints.Size(max = 200) String reason) {
    }
}
