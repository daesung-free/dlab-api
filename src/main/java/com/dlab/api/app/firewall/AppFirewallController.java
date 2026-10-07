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
    private final com.dlab.domain.user.service.AppScopeResolver scopeResolver;
    private final com.dlab.domain.firewall.repository.FirewallRequestRepository requestRepository;
    private final java.time.Clock clock;

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

    /**
     * 내 신청 이력 (A-8).
     *
     * <p>★ <b>해제중인 건의 Voucher 코드가 여기서 내려간다</b> — 학생이 와이파이에 넣는 값이다.
     * 끝난 건의 코드는 내리지 않는다(지난 코드를 계속 넣어 보게 된다).
     *
     * @param studentId <b>학부모만</b> 쓴다. 자녀가 여럿이라 서버가 고를 수 없다
     * @param from      비우면 최근 3개월
     */
    @GetMapping
    public ApiResponse<java.util.List<FirewallResponse>> history(
            @CurrentAccount AuthPrincipal principal,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate to) {

        var enrollment = scopeResolver.resolve(principal.accountId(), studentId);
        java.time.ZoneId zone = clock.getZone();
        java.time.LocalDate end = to == null ? java.time.LocalDate.now(clock) : to;
        java.time.LocalDate start = from == null ? end.minusMonths(3) : from;

        return ApiResponse.success(requestRepository.findByEnrollmentAndPeriod(
                        enrollment.getId(),
                        start.atStartOfDay(zone).toInstant(),
                        // 종료일을 포함하려면 다음 날 0시까지 본다 — 그날 신청이 빠지면
                        // "오늘 신청했는데 안 보인다"가 된다
                        end.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(FirewallResponse::from).toList());
    }
}
