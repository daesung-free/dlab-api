package com.dlab.api.app.firewall;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.firewall.service.FirewallRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 학생 앱 — 와이파이 해제 신청.
 *
 * <p>신청하면 학부모와 담당선생님에게 동시에 알림이 나가고, 승인은
 * {@code /api/v1/app/approvals}(학부모) · {@code /api/v1/admin/approvals}(담당선생님)에서 처리한다.
 */
@RestController
@RequestMapping("/api/v1/app/firewall/requests")
@RequiredArgsConstructor
public class AppFirewallController {

    private final FirewallRequestService firewallRequestService;

    @PostMapping
    public ApiResponse<FirewallResponse> create(@AuthenticationPrincipal AuthPrincipal principal,
                                                @Valid @RequestBody FirewallRequests.Create request) {
        return ApiResponse.success(FirewallResponse.from(
                firewallRequestService.createForAccount(
                        principal.accountId(), request.requestedMinutes(), request.reason())));
    }
}
