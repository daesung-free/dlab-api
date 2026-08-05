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

    /**
     * 사유 승인 여부. {@link #finalStatus}와 <b>직교하는 축</b>이다 —
     * 결석뿐 아니라 지각·조퇴에도 붙는다.
     *
     * <p>이걸로 <b>무단과 사유 승인분을 가른다</b>:
     * <ul>
     *   <li>벌점 자동부여 — 무단만 대상</li>
     *   <li>미등원 알림 — 무단만 대상</li>
     *   <li><b>키오스크 {@code absence_cnt} — 둘 다 합산</b>(규격서·키오스크 모두 "결석 횟수"다)</li>
     * </ul>
     */
    @Column(name = "is_excused", nullable = false)
    private boolean excused;

    public AttendanceDailyStatus(Academy academy, StudentEnrollment enrollment,
                                 LocalDate attendanceDate, DailyStatus finalStatus) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.attendanceDate = attendanceDate;
        this.finalStatus = finalStatus;
    }

    public AttendanceDailyStatus(Academy academy, StudentEnrollment enrollment,
                                 LocalDate attendanceDate, DailyStatus finalStatus,
                                 boolean excused) {
        this(academy, enrollment, attendanceDate, finalStatus);
        this.excused = excused;
    }

    /**
     * 재확정. <b>배치를 다시 돌려도 행이 늘지 않게</b> 기존 행을 덮어쓴다 —
     * 사유가 뒤늦게 승인되면 무단결석이 사유결석으로 바뀌어야 한다.
     */
    public void reconfirm(DailyStatus finalStatus, boolean excused, Instant at) {
        this.finalStatus = finalStatus;
        this.excused = excused;
        this.calculatedAt = at;
    }
}
