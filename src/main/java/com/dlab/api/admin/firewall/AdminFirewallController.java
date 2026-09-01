package com.dlab.api.admin.firewall;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.firewall.entity.UnlockStatus;
import com.dlab.domain.firewall.service.FirewallAdminService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 방화벽 해제 관리 (F-4.11-10).
 *
 * <p>신청은 앱, 승인은 승인 라우팅이 한다. 여기는 관리자가 보고 손대는 쪽이다.
 *
 * <p>⚠️ <b>Nebula 실제 제어는 아직 목업이다</b>(E-1 제어 단위 미확정). 상태는 정확히
 * 바뀌지만 와이파이가 실제로 열리고 닫히지는 않는다.
 */
@Tag(name = "관리자 · 방화벽 해제 (F-4.11-10)")
@RestController
@RequestMapping("/api/v1/admin/firewall-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminFirewallController {

    private final FirewallAdminService firewallAdminService;

    /** 신청·해제 이력. 조건을 비우면 그 조건은 빠진다. */
    @GetMapping
    public ApiResponse<List<FirewallResponse.FirewallRow>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Long enrollmentId,
            @RequestParam(required = false) UnlockStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.success(
                firewallAdminService.search(me, academyId, enrollmentId, status, from, to)
                        .stream().map(FirewallResponse.FirewallRow::from).toList());
    }

    /** 현재 해제중. 종료가 임박한 순이다. */
    @GetMapping("/active")
    public ApiResponse<List<FirewallResponse.FirewallRow>> active(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId) {
        return ApiResponse.success(firewallAdminService.active(me, academyId)
                .stream().map(FirewallResponse.FirewallRow::from).toList());
    }

    /**
     * 위반 적발 등록.
     *
     * <p>2회가 되면 <b>그 자리에서 2주 신청 제한</b>이 걸린다. 해제중이었다면 즉시 차단된다.
     *
     * @param firewallRequestId 신청 없이 뚫어 쓴 경우는 비운다
     */
    @PostMapping("/violations")
    public ApiResponse<Void> recordViolation(@CurrentAccount AuthPrincipal me,
                                             @RequestParam Long enrollmentId,
                                             @RequestParam(required = false) Long firewallRequestId) {
        firewallAdminService.recordViolation(me, enrollmentId, firewallRequestId);
        return ApiResponse.empty();
    }

    /** 그 학생의 위반 이력 + 지금 걸린 제재. */
    @GetMapping("/violations")
    public ApiResponse<FirewallResponse.ViolationSummary> violations(
            @RequestParam Long enrollmentId) {
        return ApiResponse.success(FirewallResponse.ViolationSummary.of(
                firewallAdminService.violations(enrollmentId),
                firewallAdminService.activeRestriction(enrollmentId).orElse(null)));
    }

    /** 제재 해제 — 착오 등록 정정용이다. */
    @DeleteMapping("/restrictions/{restrictionId}")
    public ApiResponse<Void> liftRestriction(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long restrictionId) {
        firewallAdminService.liftRestriction(me, restrictionId);
        return ApiResponse.empty();
    }
}
