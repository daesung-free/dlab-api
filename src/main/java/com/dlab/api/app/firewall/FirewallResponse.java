package com.dlab.api.app.firewall;

import com.dlab.domain.firewall.entity.FirewallRequest;

import java.time.Instant;

public record FirewallResponse(
        Long id,
        Long approvalRequestId,
        short requestedMinutes,
        String reason,
        Instant unlockStartAt,
        Instant unlockEndAt
) {

    public static FirewallResponse from(FirewallRequest r) {
        return new FirewallResponse(
                r.getId(),
                r.getApprovalRequest().getId(),
                r.getRequestedMinutes(),
                r.getReason(),
                r.getUnlockStartAt(),
                r.getUnlockEndAt());
    }
}
