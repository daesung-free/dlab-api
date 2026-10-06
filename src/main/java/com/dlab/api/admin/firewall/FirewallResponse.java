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
     * @param voucherCode    배정된 Voucher 코드. 학생이 와이파이에 넣는 값이고,
     *                       데스크가 학생에게 다시 알려줄 때도 이 값을 본다
     * @param blockFailedAt  ★ <b>차단에 실패한 시각.</b> 값이 있으면 <b>와이파이가 열린 채
     *                       남아 있을 수 있다</b> — 화면이 반드시 드러내야 한다.
     *                       벤더가 재시도를 제공하지 않아 우리 배치가 다시 걸지만,
     *                       계속 실패하면 사람이 플랫폼에서 끊어야 한다
     * @param blockAttempts  차단 시도 횟수
     */
    public record FirewallRow(Long id, Long enrollmentId, String studentName, String studentNo,
                      short requestedMinutes, String reason, String approvalStatus,
                      String unlockStatus, Instant unlockStartAt, Instant unlockEndAt,
                      String voucherCode, Instant blockFailedAt, short blockAttempts,
                      Instant requestedAt) {

        public static FirewallRow from(FirewallRequest r) {
            return new FirewallRow(r.getId(), r.getEnrollment().getId(),
                    r.getEnrollment().getStudent().getName(),
                    r.getEnrollment().getStudentNo(),
                    r.getRequestedMinutes(), r.getReason(),
                    r.getApprovalRequest().getStatus().name(),
                    r.getUnlockStatus().name(),
                    r.getUnlockStartAt(), r.getUnlockEndAt(),
                    r.getVoucherCode(), r.getBlockFailedAt(), r.getBlockAttempts(),
                    r.getCreatedAt());
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
