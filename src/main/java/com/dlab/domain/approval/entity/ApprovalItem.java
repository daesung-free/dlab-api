package com.dlab.domain.approval.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 승인 항목별 정책.
 *
 * <p>항목마다 정책이 다르다 — 방화벽은 학부모 → 10분 → 담당선생님, 정기일정은 학부모 단독
 * (에스컬레이션 없음), 사유신청은 관리자 승인.
 */
@Getter
@Entity
@Table(name = "approval_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalItem extends BaseEntity {

    /** 방화벽 해제 승인 타임아웃 확정값(분). */
    public static final int FIREWALL_TIMEOUT_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private RequestType requestType;

    /** 1차 승인 주체. */
    @Enumerated(EnumType.STRING)
    @Column(name = "approver_type", nullable = false, length = 10)
    private ApproverType approverType;

    /** null이면 에스컬레이션 없음. */
    @Column(name = "timeout_minutes")
    private Short timeoutMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_approver_type", length = 10)
    private ApproverType escalationApproverType;

    public ApprovalItem(Academy academy, short year, RequestType requestType,
                        ApproverType approverType, Short timeoutMinutes,
                        ApproverType escalationApproverType) {
        this.academy = academy;
        this.year = year;
        this.requestType = requestType;
        this.approverType = approverType;
        this.timeoutMinutes = timeoutMinutes;
        this.escalationApproverType = escalationApproverType;
    }

    /**
     * 정책 변경.
     *
     * <p><b>이미 대기중인 신청에는 소급되지 않는다</b> — {@link ApprovalRequest}가 생성
     * 시점에 타임아웃을 복사해 갖는다. 학부모가 10분 안내를 받았는데 정책이 5분으로
     * 바뀌었다고 이미 지난 것으로 처리되면 안 된다.
     */
    public void changePolicy(ApproverType approverType, Short timeoutMinutes,
                             ApproverType escalationApproverType) {
        this.approverType = approverType;
        this.timeoutMinutes = timeoutMinutes;
        this.escalationApproverType = escalationApproverType;
    }

    public boolean hasEscalation() {
        return timeoutMinutes != null && escalationApproverType != null;
    }

    /**
     * 전년도 복사 원본. NULL이면 그 해에 새로 만든 것이다.
     * 복사본과 신규 생성분을 구분할 유일한 근거라 복사 시 반드시 채운다.
     */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }

}
