package com.dlab.api.app.firewall;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.firewall.service.FirewallRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.dlab.common.security.CurrentAccount;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 학생 앱 — 와이파이 해제 신청.
 *
 * <p>신청하면 학부모와 담당선생님에게 동시에 알림이 나가고, 승인은
 * {@code /api/v1/app/approvals}(학부모) · {@code /api/v1/admin/approvals}(담당선생님)에서 처리한다.
 */
@Tag(name = "앱 · 와이파이 해제 신청 (A-8)")
@RestController
@RequestMapping("/api/v1/app/firewall/requests")
@RequiredArgsConstructor
public class AppFirewallController {

    private final FirewallRequestService firewallRequestService;

    /**
     * 와이파이 해제 신청 (A-8).
     *
     * <p>신청하면 <b>학부모와 담당선생님에게 동시에</b> 알림이 간다. 승인 자체는 공통 승인
     * 라우팅이 처리하며, 제한시간이 지나면 담당선생님에게 넘어간다.
     */
    @PostMapping
    public ApiResponse<FirewallResponse> create(@CurrentAccount AuthPrincipal principal,
                                                @Valid @RequestBody FirewallRequests.FirewallCreate request) {
        return ApiResponse.success(FirewallResponse.from(
                firewallRequestService.createForAccount(
                        principal.accountId(), request.minutesOrZero(), request.reason(),
                        request.startAt(), request.endAt())));
    }
}
