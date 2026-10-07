package com.dlab.api.app.firewall;

import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.UnlockStatus;

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
        Instant unlockEndAt,
        /** 승인 상태. <b>해제 상태와 다르다</b> — 승인됐어도 아직 안 열렸거나 이미 끝났을 수 있다 */
        String approvalStatus,
        String unlockStatus,
        /**
         * ★ 와이파이에 입력하는 코드.
         *
         * <p><b>해제중일 때만 내려간다.</b> 끝난 건의 코드를 함께 내리면 학생이 지난 코드를
         * 계속 넣어 보고 "왜 안 되냐"가 된다. 대기중에는 아직 배정되지 않아 비어 있다.
         */
        String voucherCode
) {

    public static FirewallResponse from(FirewallRequest r) {
        boolean active = r.getUnlockStatus() == UnlockStatus.ACTIVE;
        return new FirewallResponse(
                r.getId(),
                r.getApprovalRequest().getId(),
                r.getRequestedMinutes(),
                r.getRequestedStartAt(),
                r.getRequestedEndAt(),
                r.getReason(),
                r.getUnlockStartAt(),
                r.getUnlockEndAt(),
                r.getApprovalRequest().getStatus().name(),
                r.getUnlockStatus().name(),
                active ? r.getVoucherCode() : null);
    }
}
