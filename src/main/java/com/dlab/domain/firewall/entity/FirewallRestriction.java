package com.dlab.domain.firewall.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방화벽 신청 제한 (F-4.11-10).
 *
 * <p>위반 2회면 2주간 해제 신청을 막는다.
 *
 * <p><b>제재를 행으로 남긴다 — 학생에 플래그를 두지 않는다.</b> 플래그는 "지금 막혀 있나"만
 * 답하고 <b>왜·언제부터·언제까지</b>를 못 남긴다. 학생이 항의했을 때 근거가 필요하다.
 */
@Getter
@Entity
@Table(name = "firewall_restriction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FirewallRestriction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "restricted_from", nullable = false)
    private Instant restrictedFrom;

    @Column(name = "restricted_until", nullable = false)
    private Instant restrictedUntil;

    /** 제재 시점의 누적 위반 수. 나중에 위반이 더 쌓여도 이 값은 그때의 근거로 남는다. */
    @Column(name = "violation_count", nullable = false)
    private int violationCount;

    public FirewallRestriction(StudentEnrollment enrollment, Instant from, Instant until,
                               int violationCount) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.restrictedFrom = from;
        this.restrictedUntil = until;
        this.violationCount = violationCount;
    }

    public boolean isActiveAt(Instant at) {
        return !at.isBefore(restrictedFrom) && at.isBefore(restrictedUntil);
    }
}
