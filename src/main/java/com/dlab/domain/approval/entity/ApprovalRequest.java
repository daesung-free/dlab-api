package com.dlab.domain.approval.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 승인 요청 건.
 *
 * <p>승인 모델은 단순 "먼저 승인한 쪽이 이김" 레이스가 아니라 <b>타임아웃 기반 에스컬레이션</b>이다.
 * 기본 승인자는 학부모이고, 타임아웃이 지나면 담당선생님이 에스컬레이션 승인한다. 다만 타임아웃
 * 전에도 담당선생님이 먼저 승인할 수 있고, 그 경우 학부모에게 나가는 문구가 달라야 하므로
 * 결과를 {@link ResolutionCase}로 남긴다.
 *
 * <p>{@code year} 컬럼이 없는 것은 의도된 것이다 — {@code enrollment}가 이미 연도를 내포한다.
 *
 * <p>타임아웃 값과 에스컬레이션 대상은 <b>신청 시점 스냅샷</b>이다. 정책이 바뀌거나 반 담임이
 * 교체돼도 이미 처리된 건의 이력이 흔들리면 안 되기 때문이다.
 */
@Getter
@Entity
@Table(name = "approval_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_item_id", nullable = false)
    private ApprovalItem approvalItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApprovalStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    // ── 정책 스냅샷 ──

    @Column(name = "timeout_minutes", nullable = false)
    private short timeoutMinutes;

    /** requestedAt + timeoutMinutes. 승인 케이스 판별의 기준선. */
    @Column(name = "escalation_at", nullable = false)
    private Instant escalationAt;

    /**
     * 신청 시점의 담당선생님 스냅샷. 반 배정에서 자동 도출되며, 미배정이면 null이다.
     * 직원(Employee)이 아니라 선생님(Teacher)을 참조한다 — 에스컬레이션 대상은 항상 담당선생님이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_teacher_id")
    private Teacher escalationTeacher;

    // ── 처리 결과 ──

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolver_type", length = 10)
    private ApproverType resolverType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolver_account_id")
    private Account resolverAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_case", length = 30)
    private ResolutionCase resolutionCase;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    public ApprovalRequest(Academy academy, ApprovalItem approvalItem,
                           StudentEnrollment enrollment, Teacher escalationTeacher,
                           Instant requestedAt) {
        this.academy = academy;
        this.approvalItem = approvalItem;
        this.enrollment = enrollment;
        this.escalationTeacher = escalationTeacher;
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = requestedAt;
        this.timeoutMinutes = approvalItem.getTimeoutMinutes() == null
                ? 0 : approvalItem.getTimeoutMinutes();
        this.escalationAt = requestedAt.plusSeconds(this.timeoutMinutes * 60L);
    }

    /**
     * 승인 시각과 주체로 3케이스 중 무엇인지 판별한다.
     * 기준은 "승인 시각 vs 신청시각 + 타임아웃"이다.
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
        return status == ApprovalStatus.PENDING;
    }
}
