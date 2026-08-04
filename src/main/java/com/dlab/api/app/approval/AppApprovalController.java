package com.dlab.api.app.approval;

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
}
