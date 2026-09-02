package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 직원 근태 — 키오스크 출퇴근.
 *
 * <h2>학생 출결 원장과 분리한다</h2>
 * 같이 쌓으면 학생 출결 조회·통계·순공 계산에서 <b>매번 직원을 걸러야 하고</b>, 한 곳만
 * 빠뜨려도 직원이 학생 통계에 조용히 섞인다. 무엇보다 근태는 노동법 영역이라 보존기간·
 * 열람권한이 출결과 다르게 간다.
 *
 * <h2>출근/퇴근만 있다</h2>
 * 지각·조퇴 판정을 하지 않는다 — 근무시간 마스터가 있어야 하는데 요구에 없다.
 * 기록만 남기고 판정은 하지 않는다.
 */
@Getter
@Entity
@Table(name = "staff_attendance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAttendance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10)
    private StaffAttendanceType eventType;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public StaffAttendance(StudentEnrollment enrollment, LocalDate workDate,
                           StaffAttendanceType eventType, Instant recordedAt) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.workDate = workDate;
        this.eventType = eventType;
        this.recordedAt = recordedAt;
    }
}
