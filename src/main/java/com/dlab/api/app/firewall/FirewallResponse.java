package com.dlab.api.app.firewall;

import com.dlab.domain.firewall.entity.FirewallRequest;

import java.time.Instant;

public record FirewallResponse(
        Long id,
        Long approvalRequestId,
        short requestedMinutes,
        /** 학생이 지정한 구간. 비어 있으면 승인 즉시 시작이다 */
        Instant requestedStartAt,
        Instant requestedEndAt,
        String reason,
        Instant unlockStartAt,
        Instant unlockEndAt
) {

    public static FirewallResponse from(FirewallRequest r) {
        return new FirewallResponse(
                r.getId(),
                r.getApprovalRequest().getId(),
                r.getRequestedMinutes(),
                r.getRequestedStartAt(),
                r.getRequestedEndAt(),
                r.getReason(),
                r.getUnlockStartAt(),
                r.getUnlockEndAt());
    }
}
