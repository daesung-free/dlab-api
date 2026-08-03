package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일자 집계 — 배치가 태깅 원장을 보고 확정하는 파생 상태.
 * ABSENT는 오직 여기에만 존재한다.
 */
@Getter
@Entity
@Table(name = "attendance_daily_status")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceDailyStatus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 20)
    private DailyStatus finalStatus;

    /** 순공시간(분). 하원(T) 태깅이 있어야 계산된다. */
    @Column(name = "study_minutes")
    private Integer studyMinutes;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt = Instant.now();

    public AttendanceDailyStatus(Academy academy, StudentEnrollment enrollment,
                                 LocalDate attendanceDate, DailyStatus finalStatus) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.attendanceDate = attendanceDate;
        this.finalStatus = finalStatus;
    }
}
