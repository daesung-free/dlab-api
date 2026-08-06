package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 특강 신청.
 *
 * <p>정원이 차면 같은 행이 {@link ApplicationStatus#WAITLISTED}로 들어가고, 자리가 나면
 * {@link ApplicationStatus#APPLIED}로 승격된다 — <b>대기자 테이블을 따로 두지 않는다.</b>
 *
 * <p>⚠️ 여기의 "대기자"는 <b>F-4.2 입학 대기자와 다른 것</b>이다. 시트도
 * *"DSA '특강관리&gt;대기자 접수'는 대상이 다름(특강 대기자)"*이라 명시했다.
 */
@Getter
@Entity
@Table(name = "lecture_application")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureApplication extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    /** 대기 순번의 근거. 승격돼도 바뀌지 않는다. */
    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(length = 200)
    private String memo;

    public LectureApplication(Lecture lecture, StudentEnrollment enrollment,
                              ApplicationStatus status, Instant appliedAt) {
        this.academy = lecture.getAcademy();
        this.year = lecture.getYear();
        this.lecture = lecture;
        this.enrollment = enrollment;
        this.status = status;
        this.appliedAt = appliedAt;
    }

    public void cancel(Instant at) {
        this.status = ApplicationStatus.CANCELED;
        this.canceledAt = at;
    }

    /**
     * 대기 → 확정 승격.
     *
     * <p>{@code appliedAt}을 건드리지 않는다 — 대기 순번의 근거이자 "언제 신청했나"의 기록이다.
     */
    public void promote() {
        this.status = ApplicationStatus.APPLIED;
    }

    public void updateMemo(String memo) {
        this.memo = memo;
    }

    public boolean isWaitlisted() {
        return status == ApplicationStatus.WAITLISTED;
    }
}
