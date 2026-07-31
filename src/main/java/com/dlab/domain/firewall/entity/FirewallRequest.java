package com.dlab.domain.firewall.entity;

import com.dlab.common.entity.BaseTimeEntity;
import com.dlab.domain.user.entity.Branch;
import com.dlab.domain.user.entity.Staff;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.UserAccount;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 방화벽(와이파이) 해제 신청.
 *
 * <p>승인 모델은 단순 "먼저 승인한 쪽이 이김" 레이스가 아니라 <b>타임아웃 기반 에스컬레이션</b>이다
 * (CLAUDE.md §3). 기본 승인자는 학부모이고, 타임아웃(현재 10분)이 지나면 담당선생님(사감)이
 * 에스컬레이션 승인한다. 다만 타임아웃 전에도 담당선생님이 먼저 승인할 수 있고, 그 경우 학부모에게
 * 나가는 안내 문구가 달라야 하므로 결과를 {@link ResolutionCase}로 남긴다.
 *
 * <p>타임아웃 값과 에스컬레이션 승인자는 <b>신청 시점 스냅샷</b>으로 박아둔다. 나중에 정책이
 * 바뀌거나 반 담임이 교체돼도 이미 처리된 신청의 이력이 흔들리면 안 되기 때문이다.
 */
@Getter
@Entity
@Table(name = "firewall_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FirewallRequest extends BaseTimeEntity {

    /** 확정된 기본 타임아웃(분). CLAUDE.md §2 결정로그. */
    public static final int DEFAULT_TIMEOUT_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FirewallStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    // ── 승인자 · 타임아웃 정책 (신청 시점 스냅샷) ──

    /** 기본 승인자 유형. 현재 정책상 항상 PARENT지만 정책 변경 여지를 위해 값으로 둔다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_approver_type", nullable = false, length = 20)
    private ApproverType defaultApproverType;

    @Column(name = "timeout_minutes", nullable = false)
    private int timeoutMinutes;

    /** requestedAt + timeoutMinutes. 승인 시각을 이 값과 비교해 케이스를 판별한다. */
    @Column(name = "escalation_at", nullable = false)
    private Instant escalationAt;

    /** 에스컬레이션 승인자(담당선생님·사감). 반 미배정/담임 미지정이면 null일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_staff_id")
    private Staff escalationStaff;

    // ── 처리 결과 ──

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolver_account_id")
    private UserAccount resolverAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolver_type", length = 20)
    private ApproverType resolverType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_case", length = 30)
    private ResolutionCase resolutionCase;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    public FirewallRequest(Branch branch, Student student, String reason,
                           int timeoutMinutes, Staff escalationStaff, Instant requestedAt) {
        this.branch = branch;
        this.student = student;
        this.reason = reason;
        this.status = FirewallStatus.PENDING;
        this.requestedAt = requestedAt;
        this.defaultApproverType = ApproverType.PARENT;
        this.timeoutMinutes = timeoutMinutes;
        this.escalationAt = requestedAt.plusSeconds(timeoutMinutes * 60L);
        this.escalationStaff = escalationStaff;
    }

    /**
     * 승인 시각과 주체로 3케이스 중 무엇인지 판별한다.
     * 판별 기준은 "승인 시각 vs 신청시각+타임아웃"이다(CLAUDE.md §3).
     */
    public ResolutionCase decideResolutionCase(ApproverType approverType, Instant approvedAt) {
        if (approverType == ApproverType.PARENT) {
            return ResolutionCase.PARENT_IN_TIME;
        }
        return approvedAt.isBefore(escalationAt)
                ? ResolutionCase.STAFF_BEFORE_TIMEOUT
                : ResolutionCase.STAFF_AFTER_TIMEOUT;
    }

    public boolean isPending() {
        return status == FirewallStatus.PENDING;
    }
}
