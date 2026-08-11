package com.dlab.api.app.approval;

import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.entity.ResolutionCase;

import java.time.Instant;

/**
 * 승인 요청 응답.
 *
 * <p>{@code resolutionCase}를 그대로 내려준다 — 클라이언트가 케이스별로 다른 문구를 보여줘야
 * 하기 때문이다. 특히 STAFF_AFTER_TIMEOUT("시간이 지나 담임이 승인")과
 * STAFF_BEFORE_TIMEOUT("시간이 남았지만 담임이 먼저 승인")은 학부모 입장에서 전혀 다른
 * 상황이므로 <b>같은 문구로 합치면 안 된다</b>.
 */
public record ApprovalResponse(
        Long id,
        RequestType requestType,
        ApprovalStatus status,
        String studentName,
        Instant requestedAt,
        Instant escalationAt,
        short timeoutMinutes,
        /** 이 건의 우선 승인자. 화면이 "누가 처리해야 하는지"를 이걸로 표시한다 */
        ApproverType primaryApprover,
        /** 자동 재요청 발송 시각. null이면 아직 안 나갔다 */
        Instant reminderSentAt,
        /** 직원에게 넘어간 시각. null이면 아직 학부모 차례다 */
        Instant handedOverAt,
        Instant resolvedAt,
        ResolutionCase resolutionCase,
        String rejectReason
) {

    public static ApprovalResponse from(ApprovalRequest r) {
        return new ApprovalResponse(
                r.getId(),
                r.getApprovalItem().getRequestType(),
                r.getStatus(),
                r.getEnrollment().getStudentName(),
                r.getRequestedAt(),
                r.getEscalationAt(),
                r.getTimeoutMinutes(),
                r.getPrimaryApprover(),
                r.getReminderSentAt(),
                r.getHandedOverAt(),
                r.getResolvedAt(),
                r.getResolutionCase(),
                r.getRejectReason());
    }
}
