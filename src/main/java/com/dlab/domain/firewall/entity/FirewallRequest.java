package com.dlab.domain.firewall.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 와이파이 방화벽 해제 신청.
 *
 * <p><b>승인 로직을 갖지 않는다</b> — {@link ApprovalRequest}에 위임하고 여기는 해제 자체의
 * 고유 정보(요청 시간·해제 구간·Zyxel 대상)만 담는다. 사유신청·정기일정도 같은 승인 엔진을
 * 타므로, 승인 로직을 여기 두면 세 번 짜게 된다.
 */
@Getter
@Entity
@Table(name = "firewall_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FirewallRequest extends BaseEntity {

    /** 최대 해제 시간(분) = 5시간. */
    public static final int MAX_REQUESTED_MINUTES = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** 승인 상태·케이스는 전부 여기서 읽는다. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_request_id", nullable = false, unique = true)
    private ApprovalRequest approvalRequest;

    @Column(name = "requested_minutes", nullable = false)
    private short requestedMinutes;

    @Column(length = 500)
    private String reason;

    @Column(name = "unlock_start_at")
    private Instant unlockStartAt;

    @Column(name = "unlock_end_at")
    private Instant unlockEndAt;

    /** Nebula API 호출 대상. 크레덴셜·제어 단위는 E-1 미해결. */
    @Column(name = "zyxel_site_id", length = 32)
    private String zyxelSiteId;

    public FirewallRequest(Academy academy, StudentEnrollment enrollment,
                           ApprovalRequest approvalRequest, short requestedMinutes, String reason) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.approvalRequest = approvalRequest;
        this.requestedMinutes = requestedMinutes;
        this.reason = reason;
    }

    /** 승인 확정 후 실제 해제 구간을 기록한다. */
    public void activate(Instant startAt) {
        this.unlockStartAt = startAt;
        this.unlockEndAt = startAt.plusSeconds(requestedMinutes * 60L);
    }
}
