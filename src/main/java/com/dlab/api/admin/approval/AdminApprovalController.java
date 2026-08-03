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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 관리자 웹 — 담당선생님(사감) 승인 처리.
 *
 * <p>타임아웃(10분) 전에도 승인할 수 있다. 다만 그 경우 학부모에게 나가는 문구가
 * 달라야 하므로({@code STAFF_BEFORE_TIMEOUT}) 서비스가 케이스를 판별해 기록한다.
 */
@RestController
@RequestMapping("/api/v1/admin/approvals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('TEACHER')")
public class AdminApprovalController {

    private final ApprovalService approvalService;
    private final ApprovalQueryService approvalQueryService;

    /** 내가 담당선생님인 승인 대기 목록. */
    @GetMapping
    public ApiResponse<List<ApprovalResponse>> pending(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(
                approvalQueryService.pendingForTeacher(principal.accountId()).stream()
                        .map(ApprovalResponse::from)
                        .toList());
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ApprovalResponse> approve(@AuthenticationPrincipal AuthPrincipal principal,
                                                 @PathVariable Long id) {
        return ApiResponse.success(
                ApprovalResponse.from(approvalService.approve(id, principal.accountId())));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ApprovalResponse> reject(@AuthenticationPrincipal AuthPrincipal principal,
                                                @PathVariable Long id,
                                                @Valid @RequestBody ApprovalRequests.Reject request) {
        return ApiResponse.success(ApprovalResponse.from(
                approvalService.reject(id, principal.accountId(), request.reason())));
    }
}
