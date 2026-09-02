package com.dlab.api.admin.firewall;

import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.FirewallRestriction;
import com.dlab.domain.firewall.entity.FirewallViolation;
import java.time.Instant;
import java.util.List;

/** 방화벽 관리 응답 (F-4.11-10). */
public final class FirewallResponse {

    private FirewallResponse() {
    }

    /**
     * 신청 한 건.
     *
     * @param approvalStatus 승인 상태. <b>{@code unlockStatus}와 다르다</b> —
     *                       승인됐어도 시간이 지나면 해제는 끝난다
     */
    public record FirewallRow(Long id, Long enrollmentId, String studentName, String studentNo,
                      short requestedMinutes, String reason, String approvalStatus,
                      String unlockStatus, Instant unlockStartAt, Instant unlockEndAt,
                      Instant requestedAt) {

        public static FirewallRow from(FirewallRequest r) {
            return new FirewallRow(r.getId(), r.getEnrollment().getId(),
                    r.getEnrollment().getStudent().getName(),
                    r.getEnrollment().getStudentNo(),
                    r.getRequestedMinutes(), r.getReason(),
                    r.getApprovalRequest().getStatus().name(),
                    r.getUnlockStatus().name(),
                    r.getUnlockStartAt(), r.getUnlockEndAt(), r.getCreatedAt());
        }
    }

    /**
     * @param restrictedUntil 지금 제재중이면 해제 시각, 아니면 {@code null}
     */
    public record ViolationSummary(int count, Instant restrictedUntil,
                                   Long restrictionId, List<ViolationRow> items) {

        public static ViolationSummary of(List<FirewallViolation> violations,
                                          FirewallRestriction restriction) {
            return new ViolationSummary(violations.size(),
                    restriction == null ? null : restriction.getRestrictedUntil(),
                    restriction == null ? null : restriction.getId(),
                    violations.stream().map(ViolationRow::from).toList());
        }
    }

    public record ViolationRow(Long id, Long firewallRequestId, Instant occurredAt) {

        static ViolationRow from(FirewallViolation v) {
            return new ViolationRow(v.getId(),
                    v.getFirewallRequest() == null ? null : v.getFirewallRequest().getId(),
                    v.getOccurredAt());
        }
    }
}
