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
 *
 * <h2>★ 우선 승인자는 학생마다 다르다 (0803 답변서)</h2>
 * 등록 시 학생이 <b>직원/학부모 중 하나를 고르고 동의</b>한다({@link ApproverPreference}).
 * 지점 정책({@link ApprovalItem#getApproverType()})은 학생이 고르지 않았을 때의 기본값이다.
 *
 * <p>흐름이 우선 승인자에 따라 갈린다:
 * <ul>
 *   <li><b>학부모 우선</b> — 요청 → 타임아웃까지 대기 → <b>1회 자동 재요청</b> →
 *       그래도 무응답이면 <b>직원에게 이양</b></li>
 *   <li><b>직원 우선</b> — 직원 판단으로 승인. 학부모에게는 <b>알림만</b> 나간다</li>
 * </ul>
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

    /**
     * 신청 시점의 우선 승인자 스냅샷.
     *
     * <p>학생 선택이 있으면 그 값, 없으면 지점 정책값이다. 학생이 나중에 바꿔도
     * 이미 처리된 건의 이력이 흔들리면 안 되므로 신청 시점에 박아둔다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_approver", nullable = false, length = 10)
    private ApproverType primaryApprover;

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

    // ── 재요청 · 이양 ──

    /**
     * 자동 재승인 요청을 보낸 시각. {@code null}이면 아직 안 보냈다.
     *
     * <p><b>이 컬럼 하나가 "1회만" 보장이다</b> — 횟수를 따로 세면 그 규칙이 코드에만 남고,
     * 스케줄러가 두 번 돌면 두 번 나간다.
     */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    /** 재요청 후에도 무응답이라 직원에게 승인권이 넘어간 시각. */
    @Column(name = "handed_over_at")
    private Instant handedOverAt;

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
                           ApproverType primaryApprover, Instant requestedAt) {
        this.academy = academy;
        this.approvalItem = approvalItem;
        this.enrollment = enrollment;
        this.escalationTeacher = escalationTeacher;
        this.primaryApprover = primaryApprover;
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = requestedAt;
        this.timeoutMinutes = approvalItem.getTimeoutMinutes() == null
                ? 0 : approvalItem.getTimeoutMinutes();
        this.escalationAt = requestedAt.plusSeconds(this.timeoutMinutes * 60L);
    }

    /**
     * 승인 시각과 주체로 어떤 케이스인지 판별한다.
     *
     * <p><b>우선 승인자가 직원이면 타임아웃을 보지 않는다</b> — 애초에 학부모가 기다리는
     * 상황이 아니라서, "시간이 남았는데 담임이 먼저" 같은 판정 자체가 성립하지 않는다.
     *
     * <p>학부모 우선일 때만 "승인 시각 vs 신청시각 + 타임아웃"을 비교한다.
     */
    public ResolutionCase decideResolutionCase(ApproverType approverType, Instant approvedAt) {
        if (approverType == ApproverType.PARENT) {
            return ResolutionCase.PARENT_IN_TIME;
        }
        if (primaryApprover == ApproverType.TEACHER) {
            return ResolutionCase.STAFF_PRIMARY;
        }
        return approvedAt.isBefore(escalationAt)
                ? ResolutionCase.STAFF_BEFORE_TIMEOUT
                : ResolutionCase.STAFF_AFTER_TIMEOUT;
    }

    /**
     * 자동 재승인 요청을 보낼 때가 됐는가.
     *
     * <p>세 조건을 모두 만족해야 한다 — <b>학부모 우선</b>이고, <b>타임아웃이 지났고</b>,
     * <b>아직 안 보냈다</b>. 직원 우선인 건에 재요청을 보내면 기다리지도 않는 학부모에게
     * 독촉이 간다.
     */
    public boolean needsReminder(Instant now) {
        return isPending()
                && primaryApprover == ApproverType.PARENT
                && reminderSentAt == null
                && !now.isBefore(escalationAt);
    }

    /**
     * 직원에게 이양할 때가 됐는가.
     *
     * <p><b>재요청을 보낸 뒤 같은 대기시간을 한 번 더 준다.</b> 재요청과 동시에 넘기면
     * 학부모가 알림을 보고 들어올 틈이 없어 재요청이 형식적인 절차가 된다.
     */
    public boolean needsHandover(Instant now) {
        return isPending()
                && primaryApprover == ApproverType.PARENT
                && reminderSentAt != null
                && handedOverAt == null
                && !now.isBefore(reminderSentAt.plusSeconds(timeoutMinutes * 60L));
    }

    public void markReminderSent(Instant at) {
        this.reminderSentAt = at;
    }

    public void markHandedOver(Instant at) {
        this.handedOverAt = at;
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING;
    }
}
