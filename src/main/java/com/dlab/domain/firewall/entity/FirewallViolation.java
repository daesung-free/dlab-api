package com.dlab.domain.firewall.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방화벽 위반 적발 (F-4.11-10).
 *
 * <p>해제 시간 외 사용·허용되지 않은 사이트 접속 등을 관리자가 적발해 기록한다.
 * 2회 쌓이면 {@link FirewallRestriction}으로 2주간 신청이 막힌다.
 *
 * <p><b>해제 신청과 연결되지 않을 수도 있다</b>({@code firewallRequest}가 {@code null}) —
 * 아예 신청 없이 뚫어 쓴 경우가 적발 대상의 대부분이다.
 */
@Getter
@Entity
@Table(name = "firewall_violation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FirewallViolation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "firewall_request_id")
    private FirewallRequest firewallRequest;

    /** 벌점을 함께 부여했다면 그 행. 규칙(I-5)이 미확정이라 지금은 {@code null}이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "penalty_point_id")
    private PenaltyPoint penaltyPoint;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public FirewallViolation(StudentEnrollment enrollment, FirewallRequest firewallRequest,
                             Instant occurredAt) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.firewallRequest = firewallRequest;
        this.occurredAt = occurredAt;
    }
}
